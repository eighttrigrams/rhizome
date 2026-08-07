(ns ui.modals.link-context-item
  (:require [reagent.core :as r]
            [clojure.string :as str]
            api))

(defn- get-component-el [] (.getElementById js/document "link-context-item-component"))

(def *selectable-contexts (r/atom {}))

(defn- sort-idx-el-id [idx] (str "part-of-sort-idx-" idx))

;; A plain integer, and only that. The roman numerals utils/sort-idx->display
;; maps to values below -1 are the convention of items.sort_idx, the field on the
;; left-hand column; this index does not share it. -1 is unset and shows as an
;; empty field, and anything that is not a number leaves here as NaN, which the
;; backend stores as unset.
(defn- part-of-idx->display [idx] (if (= idx -1) "" (str idx)))

(defn- display->part-of-idx
  [s]
  (let [s (str/trim s)]
    (if (empty? s) -1 (js/parseInt s 10))))

(defn- relation-component
  [idx {:keys [title annotation show-badge? is-part-of? part-of-sort-idx]} remove-context]
  [:div.relation
   [:div.relation-title
    [:input
     {:type :checkbox
      :title "Show a badge for this relation"
      :defaultChecked show-badge?
      :value show-badge?
      :on-click (fn [e]
                  (swap! *selectable-contexts assoc-in
                    [idx :show-badge?]
                    (not= "true" (.-value (.-target e)))))}] " " title " "
    [:span.relation-remove {:on-click (remove-context idx)} "[Remove]"]]
   [:div.relation-part-of
    [:label {:title "This context is the whole; this item is one of its parts"}
     [:input
      {:type :checkbox
       :checked (boolean is-part-of?)
       :on-change (fn [e]
                    (swap! *selectable-contexts assoc-in
                      [idx :is-part-of?]
                      (.-checked (.-target e))))}] " part of"]
    ;; Uncontrolled, and read back off the DOM at save time -- the same way the
    ;; item's own sort index is handled in item-edit. A controlled input would
    ;; have to parse every keystroke, and a half-typed number parses to NaN.
    [:input.relation-sort-idx
     {:id (sort-idx-el-id idx)
      :autoComplete :off
      :title "Position among the parts of this whole -- a number; left empty it sorts last"
      :defaultValue (part-of-idx->display (or part-of-sort-idx -1))
      :placeholder "idx"}]]
   [:input.relation-annotation
    {:value annotation
     :placeholder "Annotation"
     :on-change (fn [evt]
                  (swap! *selectable-contexts assoc-in [idx :annotation] (.-value (.-target evt))))}]])

(defn- refusal-component
  "A refused save writes nothing at all -- not the relations, and not the item's
   own fields either, since the refusal is thrown before they are written. The
   modal stays open with everything still in it, so say plainly that it is still
   unsaved rather than leaving the user to assume the rest went through."
  [refusal]
  [:div.part-of-refusal [:div refusal]
   [:div.part-of-refusal-hint "Nothing was saved. Correct the marked relation and save again."]])

(defn component
  [item refusal]
  (let [remove-context (fn [idx] #(swap! *selectable-contexts dissoc idx))]
    ;; A refusal means the save did not go through and the modal is still the one
    ;; the user was typing in, so it must not be reset from the stored item.
    ;; Belt and braces: the modal now stays mounted across a save, so this outer
    ;; fn does not run again on a refusal in the first place.
    (when-not refusal
      (reset! *selectable-contexts (:contexts (:data item))))
    (r/create-class
      {:component-did-mount #(.focus (get-component-el))
       :reagent-render ;
         ;; The refusal lives in a slot that is always there, empty or not.
         ;; Rendering it conditionally as a sibling changes how many children
         ;; this fragment has, and React reconciles unkeyed children by
         ;; position: everything below it would shift by one, be treated as a
         ;; different element, and remount -- which recreates every uncontrolled
         ;; input in the relation lines from its :defaultValue and so throws away
         ;; the sibling indices the user typed. Exactly what a refusal must not
         ;; do, and only visible in a browser.
         (fn [_item refusal]
           #_(prn @*selectable-contexts)
           [:<> [:div (when refusal [refusal-component refusal])] [:h4 "Related contexts"]
            [:div#link-context-item-component {:tabIndex 0}
             (map (fn [[idx relation]]
                    ^{:key idx} [relation-component idx relation remove-context])
               @*selectable-contexts)]])})))

(defn- read-sort-idx
  [idx relation]
  (if-let [el (.getElementById js/document (sort-idx-el-id idx))]
    (display->part-of-idx (.-value el))
    (or (:part-of-sort-idx relation) -1)))

(defn get-values
  []
  (into {}
        (map (fn [[idx relation]]
               [idx (assoc relation :part-of-sort-idx (read-sort-idx idx relation))]))
        @*selectable-contexts))

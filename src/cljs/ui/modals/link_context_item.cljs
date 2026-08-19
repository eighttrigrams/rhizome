(ns ui.modals.link-context-item
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [ui.refusal :as refusal]
            api))

(defn- get-component-el [] (.getElementById js/document "link-context-item-component"))

(def *selectable-contexts (r/atom {}))

(defn- sort-idx-el-id [idx] (str "part-of-sort-idx-" idx))

;; A plain integer, and only that. The roman numerals utils/sort-idx->display
;; maps to values below -1 are the convention of items.sort_idx, the field on the
;; left-hand column; this index does not share it. -1 is unset and shows as an
;; empty field, and anything that is not a number leaves here as NaN, which the
;; backend stores as unset.
;;
;; Public because the relation modal out in the list (ui.modals.annotation-edit)
;; offers the same field for a single edge. One convention, stated once: two
;; copies of it would be two chances to drift from what the column holds.
(defn part-of-idx->display [idx] (if (= idx -1) "" (str idx)))

(defn display->part-of-idx
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

(defn- notice-component
  "Why the save did not go through. Either way it wrote nothing -- the relations
   and the item's own fields are one transaction and the throw comes before both
   -- and the modal stays open with everything still in it, so say that plainly
   rather than leaving the user to assume the rest went through.

   :refused is the acyclicity refusal, which the user can act on. :failed is
   anything else, most reachably another writer holding the database, which they
   can only try again after."
  [{:keys [kind message]}]
  [refusal/component message
   (case kind
     :refused "Nothing was saved. Correct the marked relation and save again."
     "Nothing was saved. Everything you typed is still here — try saving again.")])

(defn component
  [item notice]
  (let [remove-context (fn [idx] #(swap! *selectable-contexts dissoc idx))]
    ;; A notice means the save did not go through and the modal is still the one
    ;; the user was typing in, so it must not be reset from the stored item.
    ;; Belt and braces: the modal now stays mounted across a save, so this outer
    ;; fn does not run again on a failed one in the first place.
    (when-not notice
      (reset! *selectable-contexts (:contexts (:data item))))
    (r/create-class
      {:component-did-mount #(.focus (get-component-el))
       :reagent-render ;
         ;; The notice lives in a slot that is always there, empty or not.
         ;; Rendering it as a sibling that appears and disappears remounted the
         ;; relation lines below it, which recreated their uncontrolled inputs
         ;; from :defaultValue and so threw away the sibling indices the user had
         ;; typed -- exactly what a failed save must not do. Measured, not
         ;; reasoned: the e2e scenario for the refusal fails without this slot
         ;; and passes with it. I do not have a confident account of the
         ;; reconciliation that makes it so, and an earlier comment here gave one
         ;; that does not hold up, so it is not restated.
         (fn [_item notice]
           #_(prn @*selectable-contexts)
           [:<> [:div (when notice [notice-component notice])] [:h4 "Related contexts"]
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

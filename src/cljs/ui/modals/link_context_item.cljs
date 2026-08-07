(ns ui.modals.link-context-item
  (:require [reagent.core :as r]
            [utils :as utils]
            api))

(defn- get-component-el [] (.getElementById js/document "link-context-item-component"))

(def *selectable-contexts (r/atom {}))

(defn- sort-idx-el-id [idx] (str "part-of-sort-idx-" idx))

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
    ;; have to round-trip every keystroke through display->sort-idx, which turns
    ;; half-typed input into NaN.
    [:input.relation-sort-idx
     {:id (sort-idx-el-id idx)
      :autoComplete :off
      :title "Position among the parts of this whole -- a number or a roman numeral"
      :defaultValue (utils/sort-idx->display (or part-of-sort-idx -1))
      :placeholder "idx"}]]
   [:input.relation-annotation
    {:value annotation
     :placeholder "Annotation"
     :on-change (fn [evt]
                  (swap! *selectable-contexts assoc-in [idx :annotation] (.-value (.-target evt))))}]])

(defn component
  [item refusal]
  (let [remove-context (fn [idx] #(swap! *selectable-contexts dissoc idx))]
    ;; A refusal means this modal is coming back up over a save that did not go
    ;; through, so what the user had typed is left standing for them to correct.
    (when-not refusal
      (reset! *selectable-contexts (:contexts (:data item))))
    (r/create-class
      {:component-did-mount #(.focus (get-component-el))
       :reagent-render ;
         (fn [_item refusal]
           #_(prn @*selectable-contexts)
           [:<> (when refusal [:div.part-of-refusal refusal]) [:h4 "Related contexts"]
            [:div#link-context-item-component {:tabIndex 0}
             (map (fn [[idx relation]]
                    ^{:key idx} [relation-component idx relation remove-context])
               @*selectable-contexts)]])})))

(defn- read-sort-idx
  [idx relation]
  (if-let [el (.getElementById js/document (sort-idx-el-id idx))]
    (utils/display->sort-idx (.-value el))
    (or (:part-of-sort-idx relation) -1)))

(defn get-values
  []
  (into {}
        (map (fn [[idx relation]]
               [idx (assoc relation :part-of-sort-idx (read-sort-idx idx relation))]))
        @*selectable-contexts))

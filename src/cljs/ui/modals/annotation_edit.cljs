(ns ui.modals.annotation-edit
  (:require [reagent.core :as r]))

(defn get-values
  [item selected-context]
  (let [global-annotation-el (.getElementById js/document "global-annotation-input")
        relation-annotation-el (.getElementById js/document "relation-annotation-input")]
    {:item-id (:id item)
     :context-id (when selected-context (:id selected-context))
     :global-annotation (when global-annotation-el (.-value global-annotation-el))
     :relation-annotation (when relation-annotation-el (.-value relation-annotation-el))}))

(defn component
  [item selected-context]
  (let [item-data (when item (or (:data item) {}))
        *global-annotation (r/atom (or (:annotation item-data) ""))
        *relation-annotation (r/atom (or (:annotation item) ""))
        in-overview? (nil? selected-context)
        *mounted (r/atom false)]
    (r/create-class
      {:component-did-mount (fn [] (reset! *mounted true))
       :component-did-update (fn [this [_ old-item old-selected-context]]
                               (let [[_ new-item new-selected-context] (r/argv this)]
                                 (when (not= (:id old-item) (:id new-item))
                                   (let [item-data (when new-item (or (:data new-item) {}))]
                                     (reset! *global-annotation (or (:annotation item-data) ""))
                                     (reset! *relation-annotation (or (:annotation new-item)
                                                                      ""))))))
       :reagent-render (fn [item selected-context]
                         (when-not @*mounted
                           (let [item-data (when item (or (:data item) {}))]
                             (reset! *global-annotation (or (:annotation item-data) ""))
                             (reset! *relation-annotation (or (:annotation item) ""))))
                         [:div [:h3 "Edit Annotations"]
                          [:input#global-annotation-input.line
                           {:type "text"
                            :value @*global-annotation
                            :auto-focus true
                            :on-change #(reset! *global-annotation (-> %
                                                                       .-target
                                                                       .-value))
                            :placeholder "Global annotation (subtitle)..."}]
                          (when-not in-overview?
                            [:input#relation-annotation-input.line
                             {:type "text"
                              :value @*relation-annotation
                              :on-change #(reset! *relation-annotation (-> %
                                                                           .-target
                                                                           .-value))
                              :placeholder
                                (str "Relation annotation for " (:title selected-context) "...")}])
                          [:p {:style {:font-size "0.9em" :color "#666" :margin-top "10px"}}
                           "Press Alt+9 to save, ESC to cancel"]])})))

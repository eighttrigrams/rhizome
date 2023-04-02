(ns ui.main.lhs
  (:require [ui.main.input :as input]
            [ui.main.lhs.context-detail :as context-detail]
            [ui.main.lhs.issue-detail :as issue-detail]
            [ui.main.lhs.list-item :as list-item]))

(defn- contexts-list [*state]
  [:ul.cards
   (doall 
    (for [context (:contexts @*state)]
      ^{:key (:id context)}
      [list-item/component *state context]))])

(defn component [_*state]
  (fn [*state]
    (cond
      (and (:preview-issue @*state)
           (not (:loading @*state))
           (not (= :contexts (:active-search @*state))))
      [:div.details-component.scrollable
       [issue-detail/preview-component (:preview-issue @*state)]]
      (= :contexts (:active-search @*state))
      [:<>
       [input/component *state]
       [:div.scrollable
        {:class :search-active}
        [contexts-list *state]]]
      (:selected-issue @*state)
      [:div.details-component.scrollable
       [issue-detail/component *state]]
      (:selected-context @*state)
      [:<>
       [context-detail/item-component *state]
       [:div.scrollable.card-shown.details-component
        [context-detail/component *state]]]
      :else
      [:div.scrollable
       [contexts-list *state]])))

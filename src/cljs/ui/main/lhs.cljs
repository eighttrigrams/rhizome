(ns ui.main.lhs
  (:require [ui.main.input :as input]
            [ui.main.lhs.context-detail :as context-detail]
            [ui.main.lhs.item-detail :as item-detail]
            [ui.main.rhs.items-list-items :as items-list-items]))

(defn- contexts-list [*state]
  [:ul.cards
   (doall 
    (for [context (:contexts @*state)]
      ^{:key (:id context)}
      [items-list-items/regular-items-list-item-component *state context nil nil {}]))])

(defn component [_*state]
  (fn [*state]
    (cond
      (and (:preview-item @*state)
           (not (:loading @*state))
           (not (= :contexts (:active-search @*state))))
      [:div.details-component.scrollable
       [item-detail/preview-component (:preview-item @*state)]]
      (= :contexts (:active-search @*state))
      [:<>
       [input/component *state]
       [:div.scrollable
        {:class :search-active}
        [contexts-list *state]]]
      (:item-view? @*state)
      [:div.details-component.scrollable
       [item-detail/component *state]]
      (:selected-context @*state)
      [:<>
       [:ul.cards [items-list-items/regular-items-list-item-component *state (:selected-context @*state) nil nil {}]]
       [:div.scrollable.card-shown.details-component
        [context-detail/component *state]]]
      :else
      [:div.scrollable
       [contexts-list *state]])))

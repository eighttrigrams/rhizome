(ns ui.main.lhs
  (:require [ui.main.input :as input]
            [ui.main.lhs.context-detail :as context-detail]
            [ui.main.lhs.item-detail :as item-detail]
            [ui.main.rhs.items-list-items :as items-list-items]
            [ui.actions :as actions]))

(defn- contexts-list [*state]
  [:ul.cards
   (doall 
    (for [context (:contexts @*state)]
      ^{:key (:id context)}
      [items-list-items/regular-items-list-item-component *state context nil nil {}]))])

(defn- backlinks-component [*state {{:keys [contexts]} :data}]
  (let [simple-items (filter
                      (fn [[id data]]
                        (and (not (:is-context? data))
                             (not ((set (map :id (:items @*state))) id))))
                      contexts)]
    (when (pos? (count simple-items))
     [:<>
      [:h3 "Backlinks"]
      [:ul
       (map 
        (fn [[id data]] 
          [:li
           {:key (:id "backlink-" id)
            :on-click (fn [_e]
                        (actions/select-context! *state {:id id} false false))}
           (:title data)])
        simple-items)]])))

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
      (:selected-item @*state)
      [:<>
       [:ul.cards [items-list-items/regular-items-list-item-component *state (:selected-item @*state) nil nil {}]]
       [:div.scrollable.card-shown.details-component
        (if (:is_context (:selected-item @*state))
          [context-detail/component *state]
          [:<>
           [backlinks-component *state (:selected-item @*state)]
           [item-detail/preview-component (:selected-item @*state)]])]]
      :else
      [:div.scrollable
       [contexts-list *state]])))

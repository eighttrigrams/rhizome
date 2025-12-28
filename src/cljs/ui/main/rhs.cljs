(ns ui.main.rhs
  (:require [ui.main.input :as input]
            [ui.main.rhs.items-list-items :as items-list-items]))

(defn- scroll-into-view
  [id]
  (js/setTimeout
    #(do (.scrollIntoView (.getElementById js/document (str "item-card-" id))
                          (clj->js {:behavior "instant" :block "center" :inline "nearest"}))
         (.scrollIntoView (.getElementById js/document (str "item-card-" id))
                          (clj->js {:behavior "smooth" :block "start" :inline "nearest"})))
    1000))

(defn- items-list-component
  [*state]
  [:ul.cards
   (map-indexed (fn [idx item] [:<> {:key (:id item)}
                                [items-list-items/regular-items-list-item-component *state item idx
                                 {:allow-delete-on-right-click? true
                                  :show-relation-annotation? true
                                  :select-fn #(scroll-into-view %)
                                  :show-context-selector? true
                                  :rhs? true}]])
                (:items @*state))])

(defn component
  [_*state]
  (fn [*state]
    (let [state @*state]
      [:<> (when (= :items (:active-search state)) [input/component *state])
       [:div.scrollable {:class (when (= :items (:active-search state)) :search-active)}
        (when (not= :description (:modal state)) [items-list-component *state])]])))

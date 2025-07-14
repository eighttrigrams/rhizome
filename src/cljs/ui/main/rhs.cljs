(ns ui.main.rhs
  (:require [ui.main.input :as input]
            [ui.main.rhs.issues-list-items :as issues-list-items]))

(defn- scroll-into-view [id]
  (js/setTimeout
   #(do
      (.scrollIntoView
       (.getElementById js/document (str "issue-card-" id))
       (clj->js
        {:behavior "instant"
         :block "center"
         :inline "nearest"}))
     (.scrollIntoView 
      (.getElementById js/document (str "issue-card-" id)) 
      (clj->js  
       {:behavior "smooth" 
        :block "start" 
        :inline "nearest" })))
   1000))

(defn- issues-list-component [*state]
  [:ul.cards
   (map-indexed
    (fn [idx issue]
      [:<>
       {:key (:id issue)}
       [issues-list-items/regular-issues-list-item-component 
        *state issue idx {:allow-delete-on-right-click? true
                          :show-relation-annotation? true
                          :select-fn #(scroll-into-view %)
                          :show-context-selector? true
                          :rhs? true}]])
    (:issues @*state))])

(defn component [_*state]
  (fn [*state]
    (let [state @*state]
      [:<>
       (when (= :issues (:active-search state))
         [input/component *state])
       [:div.scrollable
        {:class (when (= :issues (:active-search state)) :search-active)}
        (when (not= :description (:modal state))
          [issues-list-component *state])]])))

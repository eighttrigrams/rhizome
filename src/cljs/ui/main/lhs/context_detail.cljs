(ns ui.main.lhs.context-detail
  (:require [ui.actions :as actions]
            [ui.main.lhs.list-item :as list-item]
            ["react-markdown$default" :as ReactMarkdown]))

(defn item-component [*state]
  [:ul.cards
   [list-item/component *state (:selected-context @*state)]])

(defn count-issues [issues secondary-contexts]
  (let [count-reducer,,
        #(fn [count issue]
          (if (contains? (:contexts issue) %)
            (inc count)
            count))]
    (map (fn [[id title]]
           [id
            [title
             (reduce (count-reducer id) 0 issues)]])
         secondary-contexts)))

(defn- select-secondary-context [*state id]
  (fn [_]
    (swap! *state update :selected-secondary-contexts-ids
           #((if (contains? % id) disj conj) % id))
    (actions/change-secondary-contexts-selection! *state)))

(defn- select-unassigned-secondary-contexts [*state]
  (fn [_]
    (swap! *state update :unassigned-secondary-contexts-selected? not)
    (actions/change-secondary-contexts-unassigned-selected! *state)))

(defn- unassigned-secondary-contexts-component [*state]
  [:span {:style (when (:unassigned-secondary-contexts-selected? @*state)
                   {:font-weight :bold})
          :on-click (select-unassigned-secondary-contexts *state)}
   "No secondary contexts"])

(defn- secondary-contexts-component [*state]
  (let [{:keys                        [selected-secondary-contexts-ids
                                       issues
                                       unassigned-secondary-contexts-selected?
                                       aggregated-contexts 
                                       selected-context]} @*state
        secondary_contexts (into {} (remove (fn [[idx _v]]
                                              (= idx (:id selected-context))) 
                                            aggregated-contexts))] 

    [:ul
     [:li [unassigned-secondary-contexts-component *state]]
     (->> secondary_contexts
          (count-issues issues)
          (sort-by (fn [[_id [title _count]]] (.toLowerCase title)))
          (map (fn [[id [title count]]]
                 [:li
                  {:key id
                   :on-click (select-secondary-context *state id)} 
                  [:span {:style (when (contains? selected-secondary-contexts-ids id)
                                   {:font-weight :bold})} title]
                  (when (and (empty? selected-secondary-contexts-ids)
                             (not unassigned-secondary-contexts-selected?))
                    (str " (" count ")"))])))]))

(defn- select-invert-contexts [*state]
  (fn [_]
    (swap! *state update :secondary-contexts-inverted? not)
    (actions/change-secondary-contexts-inverted! *state)))

(defn- invert-component [*state]
  [:span {:style (when (:secondary-contexts-inverted? @*state)
                   {:font-weight :bold})
          :on-click (select-invert-contexts *state)}
   "Invert"])

(defn component [_*state]
  (fn [*state]
    [:<>
     [:h4 "Search mode: " 
      (case (:search_mode (:selected-context @*state))
        0 "Normal"
        1 "A->Z,0->9"
        2 "9->0,Z->A")]
     [:hr]
     [:> ReactMarkdown
      {:children (:description (:selected-context @*state))}]
     (when (:secondary_contexts (:selected-context @*state))
       [:<>
        [:hr]
        [:h2 "Secondary contexts:"]
        [invert-component *state]
        [secondary-contexts-component *state]])]))

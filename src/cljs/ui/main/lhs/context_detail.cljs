(ns ui.main.lhs.context-detail
  (:require [ui.actions :as actions]
            [ui.main.lhs.list-item :as list-item]
            ["react-markdown$default" :as ReactMarkdown]))

(defn- try-parse [item]
  (let [parsed (js/parseInt item)] 
    (if (js/isNaN parsed) 
      nil
      parsed)))

(defn- pre-process-highlighted-secondary-contexts
  [highlighted-secondary-contexts]
  (->> highlighted-secondary-contexts
       (map try-parse)
       (remove nil?)))            

(defn- sort-secondary-contexts 
  [highlighted-secondary-contexts secondary-contexts]
  (let [highlighted-secondary-contexts (pre-process-highlighted-secondary-contexts 
                                        highlighted-secondary-contexts)
        secondary-contexts             (into {} secondary-contexts)
        front                          (reduce (fn [acc val]
                                                 (if (secondary-contexts val)
                                                   (conj acc [val (secondary-contexts val)])
                                                   acc))
                                               [] highlighted-secondary-contexts)
        back                           (remove (fn [[k _v]]
                                                 (some #{k} highlighted-secondary-contexts)) secondary-contexts)]
    (concat front back)))

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

(defn re-focus []
  (when-let [el (.getElementById js/document "search-input")]
    (.focus el)))

(defn- select-secondary-context [*state id]
  (fn [_]
    (swap! *state update :selected-secondary-contexts-ids
           #((if (contains? % id) disj conj) % id))
    (actions/change-secondary-contexts-selection! *state)
    (re-focus)))

(defn- select-unassigned-secondary-contexts [*state]
  (fn [_]
    (swap! *state update :unassigned-secondary-contexts-selected? not)
    (actions/change-secondary-contexts-unassigned-selected! *state)
    (re-focus)))

(defn- unassigned-secondary-contexts-component [*state]
  [:span {:style (when (:unassigned-secondary-contexts-selected? @*state)
                   {:font-weight :bold})
          :on-click (select-unassigned-secondary-contexts *state)}
   "No secondary contexts"])

(defn- secondary-contexts-component [*state]
  (let [{:keys                        [selected-secondary-contexts-ids
                                       issues
                                       unassigned-secondary-contexts-selected?
                                       aggregated-contexts]
         {{:keys [highlighted-secondary-contexts]} :data :as selected-context} :selected-context} @*state
        secondary-contexts (remove (fn [[idx _v]]
                                     (= idx (:id selected-context))) 
                                   aggregated-contexts)]
    [:ul
     [:li [unassigned-secondary-contexts-component *state]]
     (->> secondary-contexts
          (sort-secondary-contexts highlighted-secondary-contexts)
          (count-issues issues)
          (map-indexed (fn [idx [id [title count]]]
                         [:li
                          {:key      id
                           :on-click (select-secondary-context *state id)} 
                          [:span {:style (when (contains? selected-secondary-contexts-ids id)
                                           {:font-weight :bold})} (inc idx) ". [" id "] " title]
                          (when (and (empty? selected-secondary-contexts-ids)
                                     (not unassigned-secondary-contexts-selected?))
                            (str " (" count ")"))])))]))

(defn- select-invert-contexts [*state]
  (fn [_]
    (swap! *state update :secondary-contexts-inverted? not)
    (actions/change-secondary-contexts-inverted! *state)
    (re-focus)))

(defn- select-and-contexts [*state]
  (fn [_]
    (swap! *state update :secondary-contexts-and? not)
    (actions/change-secondary-contexts-and! *state)
    (re-focus)))

(defn- and-search-component [*state]
  [:span {:style (when (:secondary-contexts-and? @*state)
                   {:font-weight :bold})
          :on-click (select-and-contexts *state)}
   "And"])

(defn- invert-component [*state]
  [:span {:style (when (:secondary-contexts-inverted? @*state)
                   {:font-weight :bold})
          :on-click (select-invert-contexts *state)}
   "Invert"])

(defn component [_*state]
  (fn [*state]
    [:<>
     (when-not (:search-globally? @*state)
       [:<>
        [:h4 "Search mode: " 
         (case (:search_mode (:selected-context @*state))
           0 "Normal"
           1 "A->Z,0->9"
           2 "9->0,Z->A")]
        [:hr]])
     [:> ReactMarkdown
      {:children (:description (:selected-context @*state))}]
     (when-not (:search-globally? @*state)
       [:<>
        [:hr]
        [:h2 "Secondary contexts:"]
        [and-search-component *state]
        [:br]
        [invert-component *state]
        [secondary-contexts-component *state]])]))

(ns ui.main.lhs.context-detail
  (:require [ui.actions :as actions]
            [ui.main.lhs.list-item :as list-item]
            ["react-markdown$default" :as ReactMarkdown]))

(defn item-component [*state]
  [:ul.cards
   [list-item/component *state (:selected-context @*state)]])

(defn re-focus []
  (when-let [el (.getElementById js/document "search-input")]
    (.focus el)))

(defn- select-secondary-context [*state id]
  (fn [_]
    (swap! *state assoc-in [:selected-context :data]
           (if-not (:data (:selected-context @*state))
             {:selected-secondary-contexts [id]}
             (update (:data (:selected-context @*state)) :selected-secondary-contexts 
                     #(into [] ((if (contains? (into #{} %) id) disj conj) 
                                (into #{} %) id)))))
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
  (let [{:keys                        [unassigned-secondary-contexts-selected?
                                       aggregated-contexts]
         {{:keys [selected-secondary-contexts]} 
          :data :as selected-context} :selected-context} @*state
        secondary-contexts (remove (fn [[idx _v]]
                                     (= idx (:id selected-context))) 
                                   aggregated-contexts)]
    [:ul
     [:li [unassigned-secondary-contexts-component *state]]
     (map-indexed
      (fn [idx [id [title count highlighted?]]]
        [:li
         {:key      id
          :on-click (select-secondary-context *state id)} 
         [:span {:style (when (contains? (into #{} selected-secondary-contexts) id)
                          {:font-weight :bold})} 
          (when (and highlighted? (< idx 5))
            (str (inc idx) " ")) 
          [:span.badge {:style {:font-size "9px"}} id]
          " "
          title]
         (when (and (empty? (into #{} selected-secondary-contexts))
                    (not unassigned-secondary-contexts-selected?))
           (str " (" count ")"))]) 
      secondary-contexts)]))

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

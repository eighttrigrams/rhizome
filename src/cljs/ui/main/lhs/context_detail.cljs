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
    ;; TODO simplify
    (swap! *state assoc-in [:selected-context :data :views :current]
           (if-not (:current (:views (:data (:selected-context @*state))))
             {:selected-secondary-contexts [id]}
             (update (:current (:views (:data (:selected-context @*state)))) 
                     :selected-secondary-contexts
                     #(into [] ((if (contains? (into #{} %) id) disj conj) 
                                (into #{} %) id)))))
    (actions/change-secondary-contexts-selection! *state)
    (re-focus)))

(defn- select-unassigned-secondary-contexts [*state]
  (fn [_]
    ;; TODO simplify
    (swap! *state assoc-in [:selected-context :data :views :current]
           (if-not (:current (:views (:data (:selected-context @*state))))
             {:secondary-contexts-unassigned-selected false}
             (update (:current (:views (:data (:selected-context @*state))))
                     :secondary-contexts-unassigned-selected
                     not)))
    (actions/change-secondary-contexts-unassigned-selected! *state)
    (re-focus)))

(defn- unassigned-secondary-contexts-component [*state]
  [:span {:style 
          (when 
           (:secondary-contexts-unassigned-selected 
            (:current 
             (:views
              (:data 
               (:selected-context @*state))))) 
            {:font-weight :bold})
          :on-click (select-unassigned-secondary-contexts *state)}
   "No secondary contexts"])

(defn- secondary-contexts-component [*state]
  (let [{:keys                        [aggregated-contexts]
         {{{{:keys [selected-secondary-contexts
                    secondary-contexts-unassigned-selected]} :current} :views} 
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
          (when highlighted?
            (str
             (if (< idx 6) (inc idx) "_")
             " ")) 
          [:span.badge {:style {:font-size "9px"}} id]
          " "
          title]
         (when (and (empty? (into #{} selected-secondary-contexts))
                    (not secondary-contexts-unassigned-selected))
           (str " (" count ")"))]) 
      secondary-contexts)]))

(defn- select-invert-contexts [*state]
  (fn [_]
    ;; TODO simplify
    (swap! *state assoc-in [:selected-context :data :views :current]
           (if-not (:current (:views (:data (:selected-context @*state))))
             {:secondary-contexts-inverted false}
             (update (:current (:views (:data (:selected-context @*state))))
                     :secondary-contexts-inverted
                     not)))
    (actions/change-secondary-contexts-inverted *state)
    (re-focus)))

(defn- invert-component [*state]
  [:span {:style (when (:secondary-contexts-inverted 
                        (:current
                         (:views
                          (:data
                           (:selected-context @*state)))))
                   {:font-weight :bold})
          :on-click (select-invert-contexts *state)}
   "Invert"])

(defn- views-component [*state]
  [:ul (doall
        (map-indexed (fn [idx {:keys [title]}]
                       [:li {:key             idx
                             :style           {:font-weight (if (= (-> *state deref :selected-context :data :views :current)
                                                                   (-> *state deref :selected-context :data :views :stored (get idx) :view))
                                                              "bold"
                                                              "normal")}
                             :on-click        (fn [_] (actions/load-stored-context *state idx))
                             :on-context-menu (fn [e] 
                                                (.preventDefault e)
                                                (actions/remove-stored-context *state idx))}
                        title]) 
                     (:stored (:views (:data (:selected-context @*state))))))])

(defn component [_*state]
  (fn [*state]
    [:<>
     (when (and (not= "" (:description (:selected-context @*state)))
                (not (nil? (:description (:selected-context @*state))))
                (-> *state deref :selected-context :data :views :current :context-preview))
       [:<>
        [:> ReactMarkdown
         {:children (:description (:selected-context @*state))}]])
     (when-not (or (:search-globally? @*state)
                   (-> *state deref :selected-context :data :views :current :context-preview)) 
       [:<>
        (if (or (= 0 (:events-view (:current (:views (:data (:selected-context @*state))))))
                (nil? (:events-view (:current (:views (:data (:selected-context @*state)))))))
          [:h4 "Search mode: "
           (case (:search-mode (:current (:views (:data (:selected-context @*state)))))
             0 "Normal"
             1 "A->Z,0->9"
             2 "9->0,Z->A"
             nil "Normal")]
          [:h4 "Events View: "
           (case (:events-view (:current (:views (:data (:selected-context @*state)))))
             0 "Normal"
             1 "Events"
             2 "Archived"
             nil "Normal")])
        [views-component *state]
        [invert-component *state]
        [secondary-contexts-component *state]])]))

(ns ui.main.lhs.context-detail
  (:require [ui.actions :as actions]
            [ui.codemirror :as codemirror]
            ["react-markdown$default" :as ReactMarkdown]))

;; The editor in front of the box, when there is one -- .focus() on the element
;; itself would land on the transparent mirror and leave the caret nowhere the
;; user can see. See ui.codemirror/focus-field!.
(defn re-focus
  []
  (codemirror/focus-field! (.getElementById js/document "search-input")))

(defn- select-secondary-context
  [*state id]
  (fn [_]
    ;; TODO simplify
    (swap! *state assoc-in
      [:selected-item :data :views :current]
      (if-not (:current (:views (:data (:selected-item @*state))))
        {:selected-secondary-contexts [id]}
        (update (:current (:views (:data (:selected-item @*state))))
                :selected-secondary-contexts
                #(into [] ((if (contains? (into #{} %) id) disj conj) (into #{} %) id)))))
    (actions/change-secondary-contexts-selection! *state)
    (re-focus)))

(defn- select-unassigned-secondary-contexts
  [*state]
  (fn [_]
    ;; TODO simplify
    (swap! *state assoc-in
      [:selected-item :data :views :current]
      (if-not (:current (:views (:data (:selected-item @*state))))
        {:secondary-contexts-unassigned-selected true}
        (update (:current (:views (:data (:selected-item @*state))))
                :secondary-contexts-unassigned-selected
                not)))
    (actions/change-secondary-contexts-unassigned-selected! *state)
    (re-focus)))

(defn- unassigned-secondary-contexts-component
  [*state]
  [:span
   {:style (when (:secondary-contexts-unassigned-selected (:current (:views (:data (:selected-item
                                                                                     @*state)))))
             {:font-weight :bold
              :text-decoration
                (if (and (not (:secondary-contexts-inverted (:current (:views (:data (:selected-item
                                                                                       @*state))))))
                         (seq (:selected-secondary-contexts
                                (:current (:views (:data (:selected-item @*state)))))))
                  :line-through
                  :initial)})
    :on-click (select-unassigned-secondary-contexts *state)} "No secondary contexts"])

(defn- secondary-contexts-component
  [*state]
  (let [{:keys [aggregated-contexts]
         {{{{:keys [selected-secondary-contexts secondary-contexts-unassigned-selected]} :current}
             :views}
            :data
          :as selected-item}
           :selected-item}
          @*state
        secondary-contexts (remove (fn [[idx _v]] (= idx (:id selected-item))) aggregated-contexts)]
    [:ul [:li [unassigned-secondary-contexts-component *state]]
     (map-indexed (fn [idx [id [{:keys [title]} count highlighted?]]]
                    [:li {:key id :on-click (select-secondary-context *state id)}
                     [:span
                      {:style (when (contains? (into #{} selected-secondary-contexts) id)
                                {:font-weight :bold})}
                      (when highlighted? (str (if (< idx 6) (inc idx) "_") " "))
                      [:span.badge {:style {:font-size "9px"}} id] " " title]
                     (when (and (empty? (into #{} selected-secondary-contexts))
                                (not secondary-contexts-unassigned-selected))
                       (str " (" count ")"))])
                  secondary-contexts)]))

(defn- select-invert-contexts
  [*state]
  (fn [_]
    ;; TODO simplify
    (swap! *state assoc-in
      [:selected-item :data :views :current]
      (if-not (:current (:views (:data (:selected-item @*state))))
        {:secondary-contexts-inverted false}
        (update (:current (:views (:data (:selected-item @*state))))
                :secondary-contexts-inverted
                not)))
    (actions/change-secondary-contexts-inverted *state)
    (re-focus)))

(defn- invert-component
  [*state]
  [:span
   {:style (when (:secondary-contexts-inverted (:current (:views (:data (:selected-item @*state)))))
             {:font-weight :bold})
    :on-click (select-invert-contexts *state)} "Invert"])

(defn- select-description-filter
  [*state value]
  (fn [_]
    (swap! *state assoc-in [:selected-item :data :views :current :description-filter] value)
    (actions/change-description-filter! *state)
    (re-focus)))

(defn- description-filter-component
  [*state]
  (let [current-filter (:description-filter (:current (:views (:data (:selected-item @*state)))))
        off? (or (nil? current-filter) (= :off current-filter) (= "off" current-filter))
        only? (or (true? current-filter) (= :only current-filter) (= "only" current-filter))
        no? (or (false? current-filter) (= :no current-filter) (= "no" current-filter))]
    [:span "Filter descriptions: "
     [:span
      {:style (when off? {:font-weight "bold"}) :on-click (select-description-filter *state nil)}
      "off"] " | "
     [:span
      {:style (when only? {:font-weight "bold"}) :on-click (select-description-filter *state true)}
      "only"] " | "
     [:span
      {:style (when no? {:font-weight "bold"}) :on-click (select-description-filter *state false)}
      "no"]]))

(defn- views-component
  [*state]
  [:ul
   (doall
     (map-indexed (fn [idx {:keys [title]}]
                    [:li
                     {:key idx
                      :style {:font-weight (if (= (-> *state
                                                      deref
                                                      :selected-item
                                                      :data
                                                      :views
                                                      :current)
                                                  (-> *state
                                                      deref
                                                      :selected-item
                                                      :data
                                                      :views
                                                      :stored
                                                      (get idx)
                                                      :view))
                                             "bold"
                                             "normal")}
                      :on-click (fn [_] (actions/load-stored-context *state idx))
                      :on-context-menu
                        (fn [e] (.preventDefault e) (actions/remove-stored-context *state idx))}
                     title])
                  (:stored (:views (:data (:selected-item @*state))))))])

(defn component
  [_*state]
  ;; Intersection and filtering -- search mode, stored views, the description
  ;; filter, invert, the secondary contexts -- is the whole of what this
  ;; renders, and none of it means anything in a hierarchy. So in hierarchy
  ;; mode it is not there at all.
  (fn [*state] (when-not (:hierarchy-mode? @*state)
                 [:<>
                  [:h4 "Search mode: "
                   (case (:search-mode (:current (:views (:data (:selected-item @*state)))))
                     0 "Normal"
                     1 "Reverse"
                     2 "0->9"
                     3 "9->0"
                     4 "Events"
                     5 "Added"
                     nil "Normal")] [views-component *state] [description-filter-component *state]
                  [:br] [:br] [invert-component *state] [secondary-contexts-component *state]])))

(ns ui.main.rhs.issues-list-items
  (:require ["react-markdown$default" :as ReactMarkdown]
            [ui.actions :as actions]
            [ui.main.rhs.context-badges :as context-badges]
            [ui.main.rhs.meta-pressed :as meta-pressed]))

(defn info-component [state issue]
  [:span.info
   (when (and (:selected-context state)
              (not= 0 (:search_mode (:selected-context state)))
              (not (and (= (:short_title_ints issue) 0)
                        (empty? (:short_title issue)))))
     (str "["
          (if (> (:short_title_ints issue) 0)
            (:short_title_ints issue)
            (:short_title issue))
          "] "))
   [:span.date (:date issue)]])

;; TODO extract ns
(defn title-component [title]
  [:span.title
   [:> ReactMarkdown
    {:children title}]])

(defn related-issues-list-item-component [*state {:keys [id title contexts] :as issue}]
  [:li.card.issue-card
   {:on-click #(do (swap! *state (fn [state] ;; TODO review and dedup with issues-list-item/component
                                   (-> state
                                       (dissoc :preview-issue))))
                   (actions/select-issue! *state {:id id}))
    :on-mouse-enter #(when-not (:loading @*state)
                       (swap! *state assoc
                              :preview-issue issue
                              :mouse :enter))
    :on-mouse-leave #(do
                       (swap! *state assoc :mouse :leave)
                       (js/setTimeout (fn [_]
                                        (when (= :leave (:mouse @*state))
                                          (swap! *state dissoc :preview-issue)))
                                      300))}
   [:div
    [title-component title]
    [info-component @*state issue]
    [context-badges/component contexts]]])

(defn regular-issues-list-item-component [*state issue idx select-fn]
  [:li.card.issue-card
   ^{:key (:id issue)}
   {:class          (when (= (:id (:selected-issue @*state))
                             (:id issue)) :selected)
    :id             (str "issue-card-" idx)
    :on-click       #(do 
                       (swap! *state (fn [state] (dissoc state :preview-issue))) 
                       (actions/select-issue! *state 
                                              issue
                                              (deref meta-pressed/*meta-pressed?))
                       (when (deref meta-pressed/*meta-pressed?)
                         (select-fn idx)))
    :on-mouse-enter #(when-not (:loading @*state)
                       (swap! *state assoc 
                              :preview-issue issue
                              :mouse :enter))
    :on-mouse-leave #(do
                       (swap! *state assoc :mouse :leave)
                       (js/setTimeout (fn [_]
                                           (when (= :leave (:mouse @*state))
                                             (swap! *state dissoc :preview-issue))
                                           )
                                        300))}
   [:div
    [title-component (:title issue)]
    [info-component @*state issue]
    [context-badges/component (remove #(= (:id (:selected-context @*state))
                                          (first %)) 
                                      (:contexts issue))]]])

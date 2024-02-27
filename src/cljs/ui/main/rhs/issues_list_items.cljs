(ns ui.main.rhs.issues-list-items
  (:require [clojure.string :as str]
            ["react-markdown$default" :as ReactMarkdown]
            [ui.actions :as actions]
            [ui.main.context-badges :as context-badges]
            [ui.main.rhs.modifiers :as modifiers]))

(defn info-component [state issue]
  [:span.info
   (when (and (:selected-context state)
              (or (nil? (:events-view (:current (:views (:data (:selected-context state))))))
                  (= 0 (:events-view (:current (:views (:data (:selected-context state)))))))
              #_(not= 0 (:search_mode (:selected-context state)))
              (not (and (= (:short_title_ints issue) 0)
                        (empty? (:short_title issue)))))
     (str "["
          (if (> (:short_title_ints issue) 0)
            (:short_title_ints issue)
            (:short_title issue))
          "] "))
   [:span.date (:date issue)]])

;; TODO extract ns
(defn title-component [title data]
  [:span.title
   [:> ReactMarkdown
    {:children (if (:substack (:resource-links data)) 
                 (let [substack-link (:substack (:resource-links data))]
                   (str "[" title "]( " substack-link ")"))
                 (str (when-let [article-link (:substack-article (:resource-links data))]
                        (str "[substack]( " article-link ")"))
                      (when-let [article-link (:web-article (:resource-links data))]
                        (str "[link]( " article-link ")"))
                      (when-let [youtube-link (:youtube-video (:resource-links data))]
                        (str "[youtube]( " youtube-link ")")) 
                      (when-let [youtube-link (:youtube-channel (:resource-links data))]
                        (str "[YT" (str/replace youtube-link "https://www.youtube.com/" "") "](" 
                             youtube-link ")"))
                      " " title))}]])

(defn related-issues-list-item-component [*state {:keys [id title] :as issue}]
  (prn "related-issues-list-item-component"
       issue)
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
    [context-badges/component (:contexts issue)]]])

(defn regular-issues-list-item-component [*state issue idx select-fn]
  (let [simple-card? (and (:notes-mode (:current (:views (:data (:selected-context @*state)))))
                          (not (-> *state deref :search-globally?)))]
    [:li.issue-card
     {:class          (str (if simple-card? 
                             "simple-card"
                             "card") (when (= (:id (:selected-issue @*state))
                                              (:id issue)) " selected"))
      :id             (str "issue-card-" idx)
      :on-click       #(let [skip-select? (and (deref modifiers/*alt-pressed?)
                                               (not= :issues (:active-search @*state)))]
                         (swap! *state (fn [state] (dissoc state :preview-issue))) 
                         (actions/select-issue! *state 
                                                issue
                                                skip-select?)
                         (when skip-select?
                           (select-fn idx)))
      :on-mouse-enter #(when-not (or (:loading @*state)
                                     simple-card?)
                         (swap! *state assoc 
                                :preview-issue issue
                                :mouse :enter))
      :on-context-menu (fn [e]
                         (.preventDefault e)
                         (if (:is_context issue)
                           (actions/select-context! *state issue)
                           (actions/delete-issue! *state issue)))
      :on-mouse-leave #(do
                         (swap! *state assoc :mouse :leave)
                         (js/setTimeout (fn [_]
                                          (when (= :leave (:mouse @*state))
                                            (swap! *state dissoc :preview-issue)))
                                        300))}
     [:div
      [title-component (:title issue) (:data issue)]
      (when-not simple-card?
        [:<>
         [info-component @*state issue]
         [context-badges/component (remove #(= (:id (:selected-context @*state))
                                               (first %)) 
                                           (merge (when (:is_context issue) 
                                                    {0 "⭕"})
                                                  (when-let [file (:file (:resource-links (:data issue)))]
                                                    {:file file})
                                                  (:contexts issue)))]])]]))

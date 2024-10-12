(ns ui.main.rhs.issues-list-items
  (:require [clojure.string :as str]
            ["react-markdown$default" :as ReactMarkdown]
            [ui.actions :as actions]
            [ui.main.context-badges :as context-badges]
            [ui.main.rhs.modifiers :as modifiers]))

;; TODO extract ns
(defn title-component [title data]
  [:span.title
   [:> ReactMarkdown
    {:children (if (:substack (:resource-links data)) 
                 (let [substack-link (:substack (:resource-links data))]
                   (str "[Substack]( " substack-link ") " title))
                 (str (when-let [article-link (:substack-article (:resource-links data))]
                        (str "[substack]( " article-link ")"))
                      (when-let [substack-author-link (:substack-author (:resource-links data))]
                        (str "[substack]( " substack-author-link ")"))
                      (when-let [substack-note-link (:substack-note (:resource-links data))]
                        (str "[substack]( " substack-note-link ")"))
                      (when-let [x-post-link (:x-post (:resource-links data))]
                        (str "[X]( " x-post-link ")"))
                      (when-let [x-handle-link (:x-handle (:resource-links data))]
                        (str "[X]( " x-handle-link ")"))
                      (when-let [article-link (:web-article (:resource-links data))]
                        (str "[link]( " article-link ")"))
                      (when-let [apple-podcast-link (:apple-podcast (:resource-links data))]
                        (str "[apple podcasts]( " apple-podcast-link ")")) 
                      (when-let [apple-pod-ep-link (:apple-podcast-episode (:resource-links data))]
                        (str "[apple podcasts]( " apple-pod-ep-link ")")) 
                      (when-let [youtube-link (:youtube-video (:resource-links data))]
                        (str "[youtube]( " youtube-link ")")) 
                      (when-let [youtube-link (:youtube-channel (:resource-links data))]
                        (str "[YT" (str/replace youtube-link "https://www.youtube.com/" "") "](" 
                             youtube-link ")"))
                      " " title))}]])

(defn- image-preview-component [data]
  (cond (:preview-image-lowres data)
               [:img {:src     (str "/imgs/Preview/Lowres/" (:preview-image-lowres data))
                      :style   {:visibility :hidden
                                :height "0px"}
                      :on-load (fn [t]
                                 (set! (-> (.-target t) .-style .-height) "180px")
                                 (set! (.. t -target -style -visibility) "visible"))}]
               (:preview-image data)
               [:img {:src     (str "/imgs/Preview/" (:preview-image data))
                      :style   {:visibility :hidden
                                :height "0px"}
                      :on-load (fn [t]
                                 (set! (-> (.-target t) .-style .-height) "180px")
                                 (set! (.. t -target -style -visibility) "visible"))}]
               (:image (:resource-links data))
               [:img {:src     (str "/imgs/" (:image (:resource-links data)))
                      :style   {:visibility :hidden
                                :height "0px"}
                      :on-load (fn [t]
                                 (set! (-> (.-target t) .-style .-height) "180px")
                                 (set! (.. t -target -style -visibility) "visible"))}]))

(defn- on-mouse-leave [*state]
  #(do
     (swap! *state assoc :mouse :leave)
     (js/setTimeout (fn [_]
                      (when (= :leave (:mouse @*state))
                        (swap! *state dissoc :preview-issue)))
                    300)))

(defn- on-mouse-enter [*state issue]
  #(when-not (:loading @*state)
                       (swap! *state assoc
                              :preview-issue issue
                              :mouse :enter)))

(defn regular-issues-list-item-component [*state issue idx select-fn]
  [:li.issue-card
   (merge {:class          (str "card"
                                (when (not (or (:preview-image-lowres (:data issue))
                                               (:preview-image (:data issue))
                                               (:image (:resource-links (:data issue)))))
                                  " simple-card")
                                (when (= (:id (:selected-issue @*state))
                                         (:id issue)) " selected"))
           :on-click       #(if idx 
                              (let [skip-select? (and (deref modifiers/*alt-pressed?)
                                                      (not= :issues (:active-search @*state)))]
                                (swap! *state (fn [state] (dissoc state :preview-issue))) 
                                (actions/select-issue! *state 
                                                       issue
                                                       skip-select?)
                                (when skip-select?
                                  (select-fn idx)))
                              (do (swap! *state (fn [state] ;; TODO review and dedup with issues-list-item/component
                                                  (-> state
                                                      (dissoc :preview-issue))))
                                  (actions/select-issue! *state issue)))
           :on-mouse-enter (on-mouse-enter *state issue)
           :on-mouse-leave (on-mouse-leave *state)}
          (when idx 
            {:id             (str "issue-card-" idx)
             :on-context-menu (fn [e]
                                (.preventDefault e) 
                                (actions/delete-issue! *state issue))}))
   [:div.issue-card-inner
    (when (or (:preview-image-lowres (:data issue))
              (:preview-image (:data issue))
              (:image (:resource-links (:data issue)))) 
      [image-preview-component (:data issue)])
    [:div.issue-card-inner-right.issue-card-inner-child
     [title-component (:title issue) (:data issue)]
     [context-badges/component (remove #(= (:id (:selected-context @*state))
                                           (first %)) 
                                       (merge (when (:is_context issue) 
                                                {0 #(actions/select-context! *state issue)})
                                              (when (:date issue)
                                                {:date issue})
                                              (when (and (:selected-context @*state)
                                                         (or (nil? (:events-view (:current (:views (:data (:selected-context @*state))))))
                                                             (= 0 (:events-view (:current (:views (:data (:selected-context @*state)))))))
                                                         (> (:short_title_ints issue) 0))
                                                {:number (:short_title_ints issue)})
                                              (when-let [file (:file (:resource-links (:data issue)))]
                                                {:file {:file file}})
                                              (:contexts (:data issue))))]]]])

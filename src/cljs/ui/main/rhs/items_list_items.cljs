(ns ui.main.rhs.items-list-items
  (:require [clojure.string :as str]
            ["react-markdown$default" :as ReactMarkdown]
            [ui.actions :as actions]
            [ui.main.context-badges :as context-badges]
            [ui.main.rhs.modifiers :as modifiers]
            [utils :as utils]))

(defn title-component
  [title data]
  [:span.title
   [:> ReactMarkdown
    {:children (if (:substack (:resource-links data))
                 (let [substack-link (:substack (:resource-links data))]
                   (str "[substack]( " substack-link ") " title))
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
                      (when-let [github-link (:github-repo (:resource-links data))]
                        (str "[github]( " github-link ")"))
                      (when-let [github-link (:github-user (:resource-links data))]
                        (str "[github](" github-link ")"))
                      (when-let [youtube-link (:youtube-video (:resource-links data))]
                        (str "[youtube]( " youtube-link ")"))
                      (when-let [youtube-link (:youtube-channel (:resource-links data))]
                        (str "[youtube](" youtube-link ")"))
                      (when-let [website-link (:website-url (:resource-links data))]
                        (str "[website]( " website-link ")"))
                      (when-let [webpage-link (:webpage-url (:resource-links data))]
                        (str "[webpage]( " webpage-link ")"))
                      " "
                      title))}]])

(defn- image-preview-component
  [data]
  [:div.img-container
   (cond (or (and (:preview-image-lowres data) (not (:preview-image data)))
             (and (:preview-image-lowres data) (:preview-image data) (:lowres? data)))
           [:img
            {:src (str "/imgs/Preview/Lowres/" (:preview-image-lowres data))
             :style {:visibility :hidden :height "0px"}
             :on-load (fn [t]
                        (set! (-> (.-target t)
                                  .-style
                                  .-height)
                              "180px")
                        (set! (.. t -target -style -visibility) "visible"))}]
         (or (and (:preview-image data) (not (:preview-image-lowres data)))
             (and (:preview-image-lowres data) (:preview-image data) (not (:lowres? data))))
           [:img
            {:src (str "/imgs/Preview/" (:preview-image data))
             :style {:visibility :hidden :height "0px"}
             :on-load (fn [t]
                        (set! (-> (.-target t)
                                  .-style
                                  .-height)
                              "180px")
                        (set! (.. t -target -style -visibility) "visible"))}]
         (:image (:resource-links data)) [:img
                                          {:src (str "/imgs/" (:image (:resource-links data)))
                                           :style {:visibility :hidden :height "0px"}
                                           :on-load (fn [t]
                                                      (set! (-> (.-target t)
                                                                .-style
                                                                .-height)
                                                            "180px")
                                                      (set! (.. t -target -style -visibility)
                                                            "visible"))}])])

(defn- on-mouse-leave
  [*state]
  #(do (swap! *state assoc :mouse :leave)
       (js/setTimeout (fn [_]
                        (when (= :leave (:mouse @*state)) (swap! *state dissoc :preview-item)))
                      300)))

(defn- on-mouse-enter
  [*state item]
  #(when-not (:loading @*state)
     (swap! *state assoc :preview-item item :mouse :enter)
     (actions/fetch-item-description! *state item)))

(defn regular-items-list-item-component
  [*state item idx
   {:keys [allow-delete-on-right-click? show-relation-annotation? select-fn show-context-selector?
           rhs? select-as-context?]
    :as _opts}]
  [:li.item-card
   (merge
     {:class (str "card"
                  (when (not (or (:preview-image-lowres (:data item))
                                 (:preview-image (:data item))
                                 (:image (:resource-links (:data item)))))
                    " simple-card")
                  ;; TODO is there some superfluous css now?
                  #_(when (= (:id (:selected-item @*state)) (:id item)) " selected"))
      :on-click (fn [e]
                  ;; Check if the click originated from the annotation area
                  (when-not
                    (or (.. e -target -classList (contains "relation-annotation"))
                        (.. e -target -parentElement -classList (contains "relation-annotation")))
                    (if idx
                      (let [skip-select? (and (deref modifiers/*alt-pressed?)
                                              (not= :items (:active-search @*state)))]
                        (swap! *state (fn [state] (dissoc state :preview-item)))
                        (if skip-select?
                          (do (actions/reprioritize-item *state item) (select-fn idx))
                          (actions/select-item! *state item)))
                      (do (swap! *state (fn [state]
                                          (-> state
                                              (dissoc :preview-item))))
                          (if select-as-context?
                            (actions/select-context! *state item)
                            (actions/select-item! *state item))))))
      :on-mouse-enter (when idx (on-mouse-enter *state item))
      :on-mouse-leave (when idx (on-mouse-leave *state))}
     (when (and idx
                allow-delete-on-right-click?
                (or @modifiers/*alt-pressed? (:selected-item @*state)))
       {:id (str "item-card-" idx)
        :on-context-menu (fn [e]
                           (.preventDefault e)
                           (if (.-altKey e)
                             (actions/delete-item! *state item)
                             (actions/unlink-item! *state item)))}))
   (when show-relation-annotation?
     [:div
      {:class (str "relation-annotation" (when (empty? (:annotation item)) " empty-annotation"))
       :style {:z-index 10 :cursor "pointer"}
       :on-click (fn [e]
                   (.stopPropagation e)
                   (.preventDefault e)
                   (js/console.log "Annotation clicked for item:" (clj->js item))
                   (actions/open-annotation-edit-modal! *state item)
                   false)}
      (if (not-empty (:annotation item))
        (:annotation item)
        [:span {:style {:color "#888" :font-style "italic"}} "Click to add annotation"])])
   [:div.item-card-inner
    (when (or (:preview-image-lowres (:data item))
              (:preview-image (:data item))
              (:image (:resource-links (:data item))))
      [image-preview-component (:data item)])
    [:div.item-card-inner-right.item-card-inner-child
     [title-component
      (if-not (empty? (:title item)) (:title item) (if-not (empty? (:date item)) (:date item) ""))
      (:data item)]
     [context-badges/component *state
      (remove #(= (:id (:selected-item @*state)) (first %))
        (merge
          (when (and (:is_context item) show-context-selector?)
            {0 {:context #(actions/select-context! *state item)}})
          (when (or (:date item)
                    (and rhs?
                         (:selected-item @*state)
                         (= 5 (:search-mode (:current (:views (:data (:selected-item @*state))))))))
            {:date
               (if (and rhs?
                        (:selected-item @*state)
                        (= 5 (:search-mode (:current (:views (:data (:selected-item @*state)))))))
                 (when (and (:inserted_at item) (instance? js/Date (:inserted_at item)))
                   (assoc item :date (first (str/split (.toISOString (:inserted_at item)) #"T"))))
                 item)})
          (when (and (:selected-item @*state) rhs? (not= (:sort_idx item) -1))
            {:number {:number (utils/sort-idx->display (:sort_idx item))}})
          (when-let [file (:file (:resource-links (:data item)))] {:file {:file file}})
          (:contexts (:data item))))]]]])

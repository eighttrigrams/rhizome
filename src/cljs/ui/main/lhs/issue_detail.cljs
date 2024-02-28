(ns ui.main.lhs.issue-detail
  (:require ["react-markdown$default" :as ReactMarkdown]
            [ui.actions :as actions]
            [clojure.string :as str]))

(defn- context-links-component [*state related-contexts]
  (when (seq related-contexts)
    [:<>
     [:h3 "Contexts"]
     [:ul
      (map (fn [[id title]]
             [:li
              {:key      id
               :on-click #(actions/select-context! *state {:id id} true)}
              title])
           related-contexts)]]))

(defn- display-youtube-video [description data]
  [:<>
   (when-let [youtube-link (:youtube-video (:resource-links data))]
     [:iframe {:width "420px" 
               :height "315px"
               :src (str/replace (str/trim youtube-link) "watch?v=" "embed/")
               :allowFullScreen true}])
   (when (and description (str/includes? description "https://www.youtube.com/watch")) 
     (let [found (re-find #"https://www.youtube.com/watch.*?\s" description)
           found (if-not found (re-find #"https://www.youtube.com/watch.*?$" description) found)
           found (str/replace (str/trim found) "watch?v=" "embed/")]
       [:iframe {:width "420px" 
                 :height "315px"
                 :src found
                 :allowFullScreen true}]))])

(defn- image-itself [image-identifier]
  [:img {:src     (str "/imgs/" image-identifier)
            :style   {:visibility :hidden
                      :width      "0px"}
            :on-load (fn [t]
                       (set! (-> (.-target t) .-style .-width) "540px")
                       (set! (.. t -target -style -visibility) "visible"))}])

(defn- image-component [title resource-links]
  [:<>
   (when-let [image-link (:image resource-links)]
     [image-itself image-link])
   (when (and
          (string? title)
          ;; TODO use condx
          (or (str/ends-with? title ".png")
              (str/ends-with? title ".jpg")
              (str/ends-with? title ".PNG")
              (str/ends-with? title ".JPG")
              (str/ends-with? title ".JPEG")
              (str/ends-with? title ".jpeg")))
     [image-itself title])])

(defn- the-issue-itself-component [{:keys [title description date data]}]
  [:<>
   (when date [:b date])
   [:span
    {:style {:font-size "35px"}}
    [:> ReactMarkdown
     {:children (str (when-let [youtube-link (:youtube-video (:resource-links data))]
                       (str "[youtube]( " youtube-link ") "))
                     (when-let [youtube-link (:youtube-channel (:resource-links data))]
                        (str "[YT" (str/replace youtube-link "https://www.youtube.com/" "") "](" 
                             youtube-link ")"))
                     " " title)}]] 
   (display-youtube-video description data)
   [image-component title (:resource-links data)]
   [:div.description
    [:> ReactMarkdown
     {:children description}]]])

(defn component [*state suppress-switcher?]
  (let [{:keys [selected-issue selected-context]} @*state
        {:keys [contexts]} selected-issue]
    [:<>
     [:h4 (when-not suppress-switcher?
            (if selected-context
              [:div
               {:on-click #(actions/deselect-issue! *state)}
               (str "[" (:title selected-context) "]")] 
              
              "[Overview]"))]
     (when-not suppress-switcher?
       [:<>
        [context-links-component *state contexts]
        [:hr]])
     [the-issue-itself-component (or selected-issue
                                     selected-context)]]))

(defn preview-component [issue]
  (the-issue-itself-component issue))

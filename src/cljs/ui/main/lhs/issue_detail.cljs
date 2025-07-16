(ns ui.main.lhs.issue-detail
  (:require ["react-markdown$default" :as ReactMarkdown]
            [clojure.string :as str]
            [ajax.core :as ajax]))

(defn- display-youtube-video [description data]
  [:<>
   (when-let [youtube-link (:youtube-video (:resource-links data))]
     [:iframe {:width "420px" 
               :height "315px"
               :src (if-not (re-matches #"https://www.youtube.com/shorts/.*" youtube-link) 
                      (str/replace (str/trim youtube-link) "watch?v=" "embed/")
                      (str "https://www.youtube.com/embed/" (first (str/split (last (str/split youtube-link #"/")) #"\?"))))
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

(defn- image-component [title data]
  (let [resource-links (:resource-links data)]
    [:<>
     (when (not (:lowres? data))
       (when-let [preview-image-link (:preview-image data)]
         [image-itself (str "Preview/" preview-image-link)]))
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
       [image-itself title])]))

(defn- the-issue-itself-component [{:keys [title description date data]}]
  [:<>
   (when date [:b date])
   [:span
    {:style {:font-size "35px"}}
    [:> ReactMarkdown
     {:children (str (when-let [youtube-link (:youtube-video (:resource-links data))]
                       (str "[youtube]( " youtube-link ") ")) 
                     (when-let [github-link (:github-repo (:resource-links data))]
                       (str "[github]( " github-link ") ")) 
                     (when-let [github-link (:github-user (:resource-links data))]
                       (str "[github]( " github-link ") ")) 
                     (when-let [youtube-link (:youtube-channel (:resource-links data))]
                        (str "[YT" (str/replace youtube-link "https://www.youtube.com/" "") "](" 
                             youtube-link ")"))
                     " " title)}]] 
   [image-component title data]
   (display-youtube-video description data)
   [:div.description
    [:> ReactMarkdown
     {:children description}]]])

(defn send-file-to-backend [file id mode]
  (let [form-data (js/FormData.)]
    (if-not (str/ends-with? (.-name file) ".png")
      (prn "file should be a png")
      (do
        (.append form-data "file" file)
        (.append form-data "id" id)
        (.append form-data "alternative-behaviour" (str (= :high mode)))
        (ajax/POST "/upload"
          {:body            form-data
           :response-format (ajax.core/raw-response-format)
           :headers         {"Accept" "application/json"}
           :handler         (fn [response] (println "Success:" response))
           :error-handler   (fn [error] (println "Error:" error))})))))

(defn drop-target [id]
  (let [drop (fn [mode]
               (fn [e]
                 ;; Prevent the default action to accept the drop
                 (.preventDefault e)
                 (let [files (.-files (.. e -dataTransfer))]
                   (send-file-to-backend (aget files 0) id mode))))
        drag-over (fn [e]
                    ;; Necessary to allow the drop
                    (.preventDefault e))]
    [:div
     {:style {:display :flex}}
     [:div {:onDrop     (drop :high)
            :onDragOver drag-over
            :style      {:border     "2px dashed #ccc"
                         :padding    "20px"
                         :flex 1
                         :margin-top "20px"}}
      "Highres here"]
     [:div {:onDrop     (drop :low)
            :onDragOver drag-over
            :style      {:border     "2px dashed #ccc"
                         :padding    "20px"
                         :flex 1
                         :margin-top "20px"}}
      "Lowres here"]]))

(defn component [*state]
  (let [{:keys [selected-context]} @*state]
    [:<>
     (when-not (or (-> selected-context :data :resource-links :image)
                   (= :description (:modal @*state)))
       [drop-target (:id selected-context)])
     [the-issue-itself-component selected-context]]))

(defn preview-component [issue]
  (the-issue-itself-component issue))

(ns ui.main.lhs.item-detail
  (:require ["react-markdown$default" :as ReactMarkdown]
            [clojure.string :as str]
            [ajax.core :as ajax]
            [ui.actions :as actions]
            [ui.main.diff :as diff]
            [ui.qr-overlay :as qr-overlay]
            [ui.replica :as replica]
            [reagent.core :as r]))

(defn- display-youtube-video
  "The embeds for an item's video, and under each one the icon that offers it as
   a QR code -- but only when `qr?`, which is to say only in the detail view.
   The preview renders the same component while the pointer rests on a row, and
   an icon there would be offering a video the reader has not asked for yet.

   The QR is given the address as the item carries it. The iframe's src is the
   embed/ rewrite of that address and is no use to a phone, so the two are read
   off the same link separately rather than one from the other."
  [description data qr?]
  [:<>
   (when-let [youtube-link (:youtube-video (:resource-links data))]
     [:<>
      [:iframe
       {:width "420px"
        :height "315px"
        :src (if-not (re-matches #"https://www.youtube.com/shorts/.*" youtube-link)
               (str/replace (str/trim youtube-link) "watch?v=" "embed/")
               (str "https://www.youtube.com/embed/"
                    (first (str/split (last (str/split youtube-link #"/")) #"\?"))))
        :allowFullScreen true}]
      (when qr? [qr-overlay/component (str/trim youtube-link)])])
   (when (and description (str/includes? description "https://www.youtube.com/watch"))
     (let [found (re-find #"https://www.youtube.com/watch.*?\s" description)
           found (if-not found (re-find #"https://www.youtube.com/watch.*?$" description) found)
           watch-link (str/trim found)
           found (str/replace watch-link "watch?v=" "embed/")]
       [:<> [:iframe {:width "420px" :height "315px" :src found :allowFullScreen true}]
        (when qr? [qr-overlay/component watch-link])]))])

(defn- image-itself
  [image-identifier]
  [:img
   {:src (str "/imgs/" image-identifier)
    :style {:visibility :hidden :width "0px"}
    :on-load (fn [t]
               (set! (-> (.-target t)
                         .-style
                         .-width)
                     "540px")
               (set! (.. t -target -style -visibility) "visible"))}])

(defn- image-component
  [title data]
  (let [resource-links (:resource-links data)]
    [:<>
     (when (not (:lowres? data))
       (when-let [preview-image-link (:preview-image data)]
         [image-itself (str "Preview/" preview-image-link)]))
     (when-let [image-link (:image resource-links)] [image-itself image-link])
     (when (and (string? title)
                ;; TODO use condx
                (or (str/ends-with? title ".png")
                    (str/ends-with? title ".jpg")
                    (str/ends-with? title ".PNG")
                    (str/ends-with? title ".JPG")
                    (str/ends-with? title ".JPEG")
                    (str/ends-with? title ".jpeg")))
       [image-itself title])]))

(defn- custom-link-component
  [*state]
  (fn [props]
    (let [href (aget props "href")
          children (aget props "children")]
      (r/as-element (if (and href (re-matches #"^\d+$" href))
                      [:a
                       {:href "#"
                        :style {:cursor "pointer"}
                        :on-click
                          (fn [e] (.preventDefault e) (actions/select-item! *state {:id href}))}
                       (into [:<>] (if (array? children) (array-seq children) [children]))]
                      [:a {:href href :target "_blank" :rel "noopener noreferrer"}
                       (into [:<>] (if (array? children) (array-seq children) [children]))])))))

(defn- custom-image-component
  [*state]
  (fn [props]
    (let [src (aget props "src")
          alt (aget props "alt")]
      (r/as-element (cond (nil? src) [:span]
                          (re-matches #"^\d+$" src)
                            [:img
                             {:src (str "/img-by-id/" src)
                              :alt (or alt "")
                              :style {:max-width "540px" :width "auto" :height "auto"}}]
                          :else [:img
                                 {:src src
                                  :alt (or alt "")
                                  :style {:max-width "540px" :width "auto" :height "auto"}}])))))

(defn- the-item-itself-component
  [*state {:keys [title description date data]} qr?]
  [:<> (when date [:b date])
   [:span {:style {:font-size "35px"}}
    [:> ReactMarkdown
     {:children (str (when-let [youtube-link (:youtube-video (:resource-links data))]
                       (str "[youtube]( " youtube-link ") "))
                     (when-let [github-link (:github-repo (:resource-links data))]
                       (str "[github]( " github-link ") "))
                     (when-let [github-link (:github-user (:resource-links data))]
                       (str "[github]( " github-link ") "))
                     (when-let [youtube-link (:youtube-channel (:resource-links data))]
                       (str "[YT"
                            (str/replace youtube-link "https://www.youtube.com/" "")
                            "]("
                            youtube-link
                            ")"))
                     " "
                     title)}]] [image-component title data]
   (display-youtube-video description data qr?)
   [:div.description
    [:> ReactMarkdown
     {:children description
      :components {:a (custom-link-component *state) :img (custom-image-component *state)}}]]])

(defn- upload-error-handler
  "A read-only replica refuses /upload, and there a refusal is normal operation
   rather than an anomaly: reporting it only to the console would leave the drop
   looking like it worked. Alerting is how a refused write is already reported in
   this UI (see ui.recording-mode, ui.replica); other failures keep going to the
   console alone, as before."
  [error]
  (println "Error:" error)
  (when-let [msg (replica/refused-write-message (:response error))]
    (js/window.alert msg)))

(defn send-file-to-backend
  [file id mode]
  (let [form-data (js/FormData.)]
    (if-not (str/ends-with? (.-name file) ".png")
      (prn "file should be a png")
      (do (.append form-data "file" file)
          (.append form-data "id" id)
          (.append form-data "alternative-behaviour" (str (= :high mode)))
          (ajax/POST "/upload"
                     {:body form-data
                      :response-format (ajax.core/raw-response-format)
                      :headers {"Accept" "application/json"}
                      :handler (fn [response] (println "Success:" response))
                      :error-handler upload-error-handler})))))

(defn drop-target
  [id]
  (let [drop (fn [mode]
               (fn [e]
                 ;; Prevent the default action to accept the drop
                 (.preventDefault e)
                 (let [files (.-files (.. e -dataTransfer))]
                   (send-file-to-backend (aget files 0) id mode))))
        drag-over (fn [e]
                    ;; Necessary to allow the drop
                    (.preventDefault e))]
    [:div {:style {:display :flex}}
     [:div
      {:onDrop (drop :high)
       :onDragOver drag-over
       :style {:border "2px dashed #ccc" :padding "20px" :flex 1 :margin-top "20px"}}
      "Highres here"]
     [:div
      {:onDrop (drop :low)
       :onDragOver drag-over
       :style {:border "2px dashed #ccc" :padding "20px" :flex 1 :margin-top "20px"}}
      "Lowres here"]]))

(defn- version-navigation-controls
  [*state item-descriptions version-idx item-at-idx]
  (let [revisions (diff/description-revisions item-descriptions)
        diffable? (>= (count revisions) 2)]
    [:div
     {:style {:display "flex"
              :align-items "center"
              :gap "10px"
              :margin "10px 0"
              :padding "5px"
              :background-color "#f0f0f0"
              :border-radius "5px"}}
     [:button
      {:on-click #(swap! *state update :description-version-idx inc)
       :disabled (or (nil? item-descriptions) (>= version-idx (dec (count item-descriptions))))
       :style {:cursor (if (or (nil? item-descriptions)
                               (>= version-idx (dec (count item-descriptions))))
                         "not-allowed"
                         "pointer")}} "←"]
     [:button
      {:on-click #(swap! *state update :description-version-idx dec)
       :disabled (<= version-idx 0)
       :style {:cursor (if (<= version-idx 0) "not-allowed" "pointer")}} "→"]
     [:span {:style {:font-weight "bold"}}
      (if item-descriptions
        (let [db-version (:version item-at-idx)]
          (str "Version " (or db-version (inc version-idx))
               (when (= version-idx 0) " (current)")
               (when-let [source (:source item-at-idx)] (str " · " source))))
        "Version 1 (current)")]
     [:button
      {:on-click #(swap! *state assoc
                    :diff-view? true
                    :diff-version-idx (diff/version-idx->diff-idx revisions
                                                                  (:version item-at-idx)))
       :disabled (not diffable?)
       :style {:cursor (if diffable? "pointer" "not-allowed")}} "Diff"]]))

(defn component
  [*state]
  (let [{:keys [selected-item item-descriptions description-version-idx]} @*state
        version-idx (or description-version-idx 0)
        item-at-idx (when item-descriptions (nth item-descriptions version-idx nil))
        current-description (:text item-at-idx)
        selected-item (cond-> selected-item
                        current-description (assoc :description current-description)
                        (:title item-at-idx) (assoc :title (:title item-at-idx)))
        show-drop-area? (not (or (-> selected-item
                                     :data
                                     :resource-links
                                     :image)
                                 (= :description (:modal @*state))))]
    [:<> (when show-drop-area? [drop-target (:id selected-item)])
     [version-navigation-controls *state item-descriptions version-idx item-at-idx]
     [the-item-itself-component *state selected-item true]]))

(defn preview-component [*state item] [the-item-itself-component *state item false])

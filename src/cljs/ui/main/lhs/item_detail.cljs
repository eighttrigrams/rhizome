(ns ui.main.lhs.item-detail
  (:require ["react-markdown$default" :as ReactMarkdown]
            [clojure.string :as str]
            [ajax.core :as ajax]
            [ui.actions :as actions]
            [ui.floating-player :as floating-player]
            [ui.main.diff :as diff]
            [ui.qr-overlay :as qr-overlay]
            [ui.replica :as replica]
            [ui.youtube :as youtube]
            [reagent.core :as r]))

(defn- video-poster
  "The still that stands where the video used to play, and the way into the
   player.

   Not an iframe any more: a video plays in one place now, floating over the
   app (ui.floating-player), and this is a picture of one with something on it
   that reads as play. Clicking it is also the user gesture the browser wants
   before it will let the player start on its own.

   Nothing at all for an address ui.youtube cannot read a video id out of. The
   QR icon beside it is not conditional on that: the address still works on a
   phone whether or not this can name the video in it."
  [*state url]
  (when-let [id (youtube/video-id url)]
    [:div.video-poster
     {:role "button"
      :title "Play this video"
      :aria-label "Play this video"
      :on-click (fn [e] (.stopPropagation e) (floating-player/play! *state url))}
     [:img.video-poster-still {:src (youtube/poster-url id) :alt ""}]
     [:span.video-poster-play
      [:svg {:width 20 :height 22 :viewBox "0 0 12 14" :aria-hidden "true"}
       [:path {:fill "#ffffff" :d "M0 0l12 7-12 7z"}]]]]))

(defn- display-youtube-video
  "The posters for an item's video, and under each one the icon that offers it
   as a QR code -- but only when `qr?`, which is to say only in the detail view.
   The preview renders the same component while the pointer rests on a row, and
   an icon there would be offering a video the reader has not asked for yet.

   The QR is given the address as the item carries it, and the poster the same
   string: what the phone has to be sent to and what the video is identified by
   are different things, and reading one out of the other is how a phone ends up
   on a bare embed page. The embed/ rewrite that used to happen here has gone
   with the iframe, to ui.youtube/embed-url, where the player builds its src."
  [*state description data qr?]
  [:<>
   (when-let [youtube-link (:youtube-video (:resource-links data))]
     [:<> [video-poster *state (str/trim youtube-link)]
      (when qr? [qr-overlay/component (str/trim youtube-link)])])
   (when (and description (str/includes? description "https://www.youtube.com/watch"))
     (let [found (re-find #"https://www.youtube.com/watch.*?\s" description)
           found (if-not found (re-find #"https://www.youtube.com/watch.*?$" description) found)
           watch-link (str/trim found)]
       [:<> [video-poster *state watch-link] (when qr? [qr-overlay/component watch-link])]))])

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
   (display-youtube-video *state description data qr?)
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
  "The bar over the description, in two groups that answer two different
   questions.

   Left is about A VERSION -- step through them, read which one is on screen and
   where it came from, diff it against the one before. Right is about THE ITEM
   AS SUCH: its provenance, and its id. Diff compares two versions of a text;
   provenance attributes the text that is standing, whichever version the
   arrows happen to be pointing at, so the two do not belong in one row of
   buttons even though they sit on one bar.

   Each group says which it is in words, above the controls, rather than
   leaving it to the gap between them. The layout is the sort of thing a reader
   infers if he already knows the answer, and the whole point of putting
   Provenance next to Diff is that he might not.

   The id is last, at the far right, because it is the least interactive thing
   here and because it is what the group's caption is ultimately naming."
  [*state item-descriptions version-idx item-at-idx item-id]
  (let [revisions (diff/description-revisions item-descriptions)
        diffable? (>= (count revisions) 2)]
    [:div.version-bar
     [:div.version-bar-group
      [:span.version-bar-scope "this version"]
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
      [:span.version-bar-label {:style {:font-weight "bold"}}
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
        :style {:cursor (if diffable? "pointer" "not-allowed")}} "Diff"]]
     [:div.version-bar-group.version-bar-item-group
      [:span.version-bar-scope "this item"]
      [:button.provenance-open
       {:on-click #(actions/open-provenance! *state)
        :disabled (nil? item-id)
        :title "Who wrote each line of the description as it stands now"
        :style {:cursor (if item-id "pointer" "not-allowed")}} "Provenance"]
      [:span.version-bar-item-id (str "#" item-id)]]]))

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
     [version-navigation-controls *state item-descriptions version-idx item-at-idx
      (:id selected-item)]
     [the-item-itself-component *state selected-item true]]))

(defn preview-component [*state item] [the-item-itself-component *state item false])

(defn relation-preview-component
  "One relation's body text, standing where the hovered item's preview normally
   stands. The pointer is on the card the relation belongs to, so this says which
   edge it is in one small line rather than repeating the card.

   Three states, and they are not the same thing: nil text is the fetch still in
   flight (the state is set when the pointer arrives, the text lands after), a
   blank one is an edge nobody has written anything on yet, and anything else is
   the text. Saying nothing for the first two would leave an empty panel that
   reads as a fault either way."
  [{:keys [item-title context-title text]}]
  [:<>
   [:div.relation-preview-caption
    [:span.relation-preview-item item-title] " in " [:span.relation-preview-context context-title]]
   (cond (nil? text) [:div.relation-preview-note "Loading…"]
         (str/blank? text) [:div.relation-preview-note "Nothing written on this relation yet."]
         :else [:div.description [:> ReactMarkdown {:children text}]])])

(ns repository.insertion.youtube
  (:require [clojure.string :as str] 
            [cambium.core :as log]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [et.vp.ds :as datastore]
            [repository.insertion.common :as common]
            [utils.url :as url]
            upload
            scrapers.youtube))

(defn query [url]
  (json/parse-string (:body (http/get (str "https://www.youtube.com/oembed?" 
                                           (url/make-query-string {"format" "json" 
                                                               "url" url}))))
                     true))

(defn- create-channel-or-take-existing 
  [db author_name author_url youtube-channels-id]
  (let [channel-handle-simple (str/replace author_url "https://www.youtube.com/" "")
        channel-handle (str "YT" channel-handle-simple)
        channel-id (:id (datastore/get-item-by-path db "data->'resource-links'->>'youtube-channel'" author_url))
        channel-id (or channel-id
                       (let [channel (common/insert-item db 
                                                         author_name 
                                                         channel-handle
                                                         #{youtube-channels-id}
                                                         {:youtube-channel author_url})]
                         (:id channel)))]
    channel-id))

(defn match? [title]
  (or (re-matches #"https://www.youtube.com/watch\?.*v=.*" title)
      (re-matches #"https://youtu.be/.*" title)
      (re-matches #"https://www.youtube.com/shorts/.*" title)
      (re-matches #"https://youtube.com/shorts/.*" title)))

(defn- calc-urls [url]
  (if
   (or (re-matches #"https://youtube.com/shorts/.*" url)
       (re-matches #"https://www.youtube.com/shorts/.*" url))
    (let [url     (-> url 
                      (str/replace "https://youtube.com/shorts/"
                                   "https://www.youtube.com/shorts/"))
          ytvidid (first (str/split (last (str/split url #"/")) #"\?"))]
      [(url/pick-query-params (str "https://www.youtube.com/watch?v=" ytvidid) ["v"])
       (url/pick-query-params (str "https://www.youtube.com/shorts/" ytvidid) [])])
    (let [url (if (str/includes? url "youtu.be")
                (str/replace (url/pick-query-params url [])
                             "youtu.be/" "www.youtube.com/watch?v=")
                (url/pick-query-params url ["v"]))]
      [url url])))

(comment (calc-urls "https://www.youtube.com/watch?v=adkfslafj&m=13"))

(defn ingest
  [db 
   url 
   context-ids-set
   _]
  (let [[url store-url] (calc-urls url)
        {:keys [title 
                author_name
                author_url]
         :as   _response} (query url)
        _ (when-not (and (seq title)
                         (seq author_name)
                         (seq author_url))
            (log/error (str title " " author_name " " author_url))
            (throw (Exception. 
                    "at least one of title author_name or author_url is unexpectedly nil")))
        youtube-channels-id (common/get-item-or-throw-error db "YouTube Channels")
        youtube-videos-id (common/get-item-or-throw-error db "YouTube Videos")
        video-id (common/get-item-or-throw-error db "Video")
        channel-id (create-channel-or-take-existing db 
                                                    author_name
                                                    author_url
                                                    youtube-channels-id)
        existing-item (datastore/get-item-by-path db "data->'resource-links'->>'youtube-video'" store-url)
        image (:image (scrapers.youtube/get-video url))]
    (if (:id existing-item)
      (assoc (datastore/get-item db existing-item) :previously-existing-item? true)
      (let [id (:id (common/insert-item db 
                                        title
                                        ""
                                        (conj context-ids-set channel-id youtube-videos-id video-id) 
                                        {:youtube-video store-url}))]
        (when image (try (upload/upload-preview-file db {:tempfile image} id "false")
                     (catch Exception e
                       (log/error (str "problem while trying to create preview image for youtube video. message" (.getMessage e))))))))))

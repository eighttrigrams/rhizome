(ns repository.insertion.youtube
  (:require [clojure.string :as str] 
            [cheshire.core :as json]
            [clj-http.client :as http]
            [ring.util.codec :refer [url-encode]]
            datastore
            [datastore.get-item :as get-item]))

(defn make-query-string [m]
  (->> (for [[k v] m]
         (str (url-encode k) "=" (url-encode v)))
       (interpose "&")
       (apply str)))

(defn query [url]
  (json/parse-string (:body (http/get (str "https://www.youtube.com/oembed?" 
                                        (make-query-string {"format" "json" 
                                                            "url" url}))))
                  true))

(defn- create-channel-or-take-existing 
  [db author_name author_url youtube-channels-id]
  (let [channel-handle-simple (str/replace author_url "https://www.youtube.com/" "")
        channel-handle (str "YT" channel-handle-simple)
        channel-id (:id (get-item/get-item-by-path db "data->'resource-links'->>'youtube-channel'" author_url))
        channel-id (or channel-id
                       (let [channel (datastore/new-issue db 
                                                          author_name
                                                          channel-handle
                                                          youtube-channels-id
                                                          #{})
                             channel (datastore/update-issue db
                                                             {:issue              (update channel :data
                                                                                          (fn [data] (assoc data :resource-links {:youtube-channel author_url})))
                                                              :related-issues-ids '()})]
                         (:id (datastore/upgrade-issue-to-context! db channel))))]
    channel-id))

(defn- insert-video [db channel-id selected-context-id youtube-videos-id title url]
  (let [issue (datastore/new-issue db 
                                     title
                                     ""
                                     selected-context-id
                                     #{channel-id youtube-videos-id})
          issue (datastore/update-issue db {:issue (update issue :data 
                                                           (fn [data] (assoc data :resource-links {:youtube-video url})))
                                            :related-issues-ids '()})]
    issue))

;; TODO pass in a callback to create new-issue
(defn save-video
  [db 
   url 
   selected-context-id]
  (let [{:keys [title 
                author_name
                author_url] :as _response} (query url)
        youtube-channels-id (:id (get-item/get-item-by-title db {:title "YouTube Channels"}))
        youtube-videos-id (:id (get-item/get-item-by-title db {:title "YouTube Videos"}))]
    (when-not youtube-channels-id (throw (Exception. "no youtube-channels-id")))
    (when-not youtube-videos-id (throw (Exception. "no youtube-videos-id")))
    (let [channel-id (create-channel-or-take-existing db 
                                                      author_name
                                                      author_url
                                                      youtube-channels-id)
          _ (when (:id (get-item/get-item-by-path db 
                                                  "data->'resource-links'->>'youtube-video'" 
                                                  url))
              (throw (Exception. "youtube video already exists!")))]
      (insert-video db channel-id selected-context-id youtube-videos-id title url))))

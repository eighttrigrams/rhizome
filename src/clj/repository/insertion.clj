(ns repository.insertion
  (:require [clojure.string :as str] 
            [cheshire.core :as json]
            [clj-http.client :as http]
            [ring.util.codec :refer [url-encode]]
            datastore
            [datastore.get-item :as get-item]
            [datastore.issues :as issues]))

(defn- normal-issue-insertion 
  [db 
   title 
   selected-context-id
   selected-secondary-contexts-set
   split-short-title?]
  (let [parts           (if split-short-title? (str/split title #"\|") (list title))
        title           (if (= 1 (count parts)) 
                          (first parts) 
                          (second parts))
        short-title     (if (= 1 (count parts))
                          ""
                          (first parts))
        _selected-issue (datastore/new-issue db 
                                             title
                                             short-title
                                             selected-context-id
                                             selected-secondary-contexts-set)]))

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

;; TODO pass in a callback to create new-issue
(defn save-youtube-video
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
    (let [channel-handle-simple (str/replace author_url "https://www.youtube.com/" "")
          channel-handle (str "YT" channel-handle-simple)
          channel-id (:id (get-item/get-item-by-short-title db {:short_title channel-handle}))
          channel-id (or channel-id
                         (let [channel
                               (datastore/new-issue db 
                                                    (str channel-handle-simple " - " author_name)
                                                    channel-handle
                                                    youtube-channels-id
                                                    #{})
                               channel (datastore/update-issue db
                                                               {:issue (update channel :data
                                                                               (fn [data] (assoc data :resource-links {:youtube-channel author_url})))
                                                                :related-issues-ids '()})]
                           (:id (datastore/upgrade-issue-to-context! db channel))))]
      (let [issue (datastore/new-issue db 
                                       title
                                       ""
                                       selected-context-id
                                       #{channel-id youtube-videos-id})
            issue (datastore/update-issue db {:issue (update issue :data 
                                                             (fn [data] (assoc data :resource-links {:youtube-video url})))
                                              :related-issues-ids '()})]
        issue))))

(defn insert-issue 
  [db 
   title 
   selected-context 
   selected-secondary-contexts-set
   split-short-title?]
  (let [selected-context-id (:id selected-context)]
    (if (re-matches #"https://www.youtube.com/watch\?v=[.[^&]]*" title)
      (save-youtube-video db title selected-context-id)
      (normal-issue-insertion db title selected-context-id selected-secondary-contexts-set split-short-title?))))

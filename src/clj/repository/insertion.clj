(ns repository.insertion
  (:require [clojure.string :as str] 
            [cheshire.core :as json]
            [hickory.core :as html]
            [clojure.zip :as zip]
            [hickory.zip :as hickory.zip]
            [hickory.select :as select]
            [clj-http.client :as http]
            [ring.util.codec :refer [url-encode]]
            datastore
            [datastore.get-item :as get-item]))

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

(defn- get-post-title [url]
  (let [tree (html/as-hickory (html/parse (:body (http/get url))))
        title (first (:content (first (select/select (select/and (select/tag "h1")
                                                   (select/class "post-title")) tree))))]
    title))

(defn- save-substack-article [db url selected-context-id]
  (let [substacks-id (:id (get-item/get-item-by-title db {:title "Substacks"}))
        articles-id (:id (get-item/get-item-by-title db {:title "Articles"}))]
    (when-not substacks-id (throw (Exception. "no substacks-id")))
    (when-not articles-id (throw (Exception. "no articles-id")))
    (let [idx (str/index-of url ".substack")
          su (subs url 0 idx)
          subdomain (str/replace su "https://" "")]
      (let [channel-handle (str "Substack@" subdomain)
            substack-id (:id (get-item/get-item-by-short-title db {:short_title channel-handle}))
            substack-id (or substack-id
                            (let [substack (datastore/new-issue db 
                                                                (str subdomain ".substack.com")
                                                                channel-handle
                                                                substacks-id
                                                                #{})
                                  substack (datastore/update-issue db
                                                                   {:issue              (update substack :data
                                                                                                (fn [data] (assoc data :resource-links {:substack (str "https://" subdomain ".substack.com")})))
                                                                    :related-issues-ids '()})]
                              (:id (datastore/upgrade-issue-to-context! db substack))))]
        (let [title (get-post-title url)
              _ (when-not title (throw (Exception. "no post title")))
              issue (datastore/new-issue db 
                                         (get-post-title url)
                                         ""
                                         selected-context-id
                                         #{substack-id articles-id})
              issue (datastore/update-issue db {:issue (update issue :data 
                                                               (fn [data] (assoc data :resource-links {:substack-article url})))
                                                :related-issues-ids '()})]
        issue)))))

(defn insert-issue 
  [db 
   title 
   selected-context 
   selected-secondary-contexts-set
   split-short-title?]
  (let [selected-context-id (:id selected-context)]
    (cond (re-matches #"https://www.youtube.com/watch\?v=[.[^&]]*" title) 
          (save-youtube-video db title selected-context-id) 
          (re-matches #"https://.*\.substack.com\/p\/.*" title)
          (save-substack-article db title selected-context-id) 
          :else 
          (normal-issue-insertion db title selected-context-id selected-secondary-contexts-set split-short-title?))))

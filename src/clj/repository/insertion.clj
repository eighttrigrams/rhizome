(ns repository.insertion
  (:require [clojure.string :as str] 
            [cheshire.core :as json]
            [clj-http.client :as http]
            [ring.util.codec :refer [url-encode]]
            datastore))

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
   selected-context-id
   selected-secondary-contexts-set]
  (let [{:keys [title author_name] :as _response} (query url)
        title (str "[youtube](" url ") [" author_name "] " title)]
    #_(tap> [:response response])
    (datastore/new-issue db 
                         title
                         ""
                         selected-context-id
                         selected-secondary-contexts-set)))

(defn insert-issue 
  [db 
   title 
   selected-context 
   selected-secondary-contexts-set
   split-short-title?]
  (let [selected-context-id (:id selected-context)]
    (if (re-matches #"https://www.youtube.com/watch\?v=[.|[^&]]*" title)
      (save-youtube-video db title selected-context-id selected-secondary-contexts-set)
      (normal-issue-insertion db title selected-context-id selected-secondary-contexts-set split-short-title?))))

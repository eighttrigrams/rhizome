(ns repository.insertion.apple-pods
  (:require [clojure.string :as str]
            datastore
            [hickory.core :as html]
            [clj-http.client :as http]
            [hickory.select :as select]
            [datastore.get-item :as get-item]
            [repository.insertion.common :as common]
            utils))

(defn match? [title]
  (re-matches #"https:\/\/podcasts.apple.com\/.*\/podcast\/.*\/id.*\?i=.*" title))

(defn get-podcast-url [url]
  (let [idx (str/last-index-of url "?")
        rest (subs url 0 idx)]
    rest))

(defn extract-title [url]
  (let [tree (html/as-hickory (html/parse (:body (http/get url))))
        title (first (:content 
                      (first 
                       (select/select
                        (select/tag "title")
                        tree))))]
    (-> title
        (str/replace 
         "‎" "")
        (str/trim))))

(defn- create-podcast-or-take-existing 
  [db url apple-podcasts-platform-id]
  (let [podcasts-id (common/get-item-or-throw-error db "Podcasts")
        podcast-url (get-podcast-url url)
        podcast-title (extract-title podcast-url)
        channel-id (:id (get-item/get-item-by-path db "data->'resource-links'->>'apple-podcast'" podcast-url))
        channel-id (or channel-id
                       (let [channel (common/insert-item db 
                                                         podcast-title 
                                                         ""
                                                         #{podcasts-id apple-podcasts-platform-id}
                                                         {:apple-podcast podcast-url})]
                         (:id (datastore/upgrade-issue-to-context! db channel))))]
    channel-id))

(defn ingest [db url context-ids-set _should-capture-summary?]
  (let [apple-podcasts-platform-id (common/get-item-or-throw-error db "Apple Podcasts")
        podcast-id (create-podcast-or-take-existing db url apple-podcasts-platform-id)
        podcast-episodes-id (common/get-item-or-throw-error db "Podcast Episodes")]
    (when (:id (get-item/get-item-by-path db "data->'resource-links'->>'apple-podcast-episode'" url))
      (throw (Exception. "apple podcast episode already exists!")))
    (let [title (extract-title url)]
      (common/insert-item db 
                          title
                          ""
                          (conj context-ids-set 
                                podcast-id 
                                podcast-episodes-id
                                apple-podcasts-platform-id) 
                          {:apple-podcast-episode url}))))
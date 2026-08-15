(ns repository.insertion.apple-pods
  (:require [clojure.string :as str]
            [et.vp.ds :as datastore]
            [repository.insertion.common :as common]
            utils
            scrapers.apple))

(defn match?
  [title]
  (re-matches #"https:\/\/podcasts.apple.com\/.*\/podcast\/.*\/id.*\?i=.*" title))

(defn get-podcast-url [url] (let [idx (str/last-index-of url "?") rest (subs url 0 idx)] rest))

(defn extract-title
  [url]
  (let [title (:title (scrapers.apple/get-episode url))]
    (-> title
        (str/replace "‎" "")
        (str/replace #"auf.Apple.Podcasts" "")
        (str/trim)
        (str/replace #"^„" "")
        (str/replace #"“$" ""))))

(defn- create-podcast-or-take-existing
  [db url apple-podcasts-platform-id]
  (let [podcasts-id (common/get-item-or-throw-error db "Podcasts")
        podcast-url (get-podcast-url url)
        podcast-title (extract-title podcast-url)
        channel-id (:id (datastore/get-item-by-path db
                                                    "data->'resource-links'->>'apple-podcast'"
                                                    podcast-url))
        channel-id (or channel-id
                       (let [channel (common/insert-item db
                                                         podcast-title
                                                         ""
                                                         #{podcasts-id apple-podcasts-platform-id}
                                                         {:apple-podcast podcast-url})]
                         (:id channel)))]
    [channel-id podcast-title]))

(defn ingest
  [db url context-ids-set _should-capture-summary?]
  (let [apple-podcasts-platform-id (common/get-item-or-throw-error db "Apple Podcasts")
        [podcast-id podcast-title]
          (create-podcast-or-take-existing db url apple-podcasts-platform-id)
        podcast-episodes-id (common/get-item-or-throw-error db "Podcast Episodes")
        existing-item
          (datastore/get-item-by-path db "data->'resource-links'->>'apple-podcast-episode'" url)]
    ;; An episode we already hold is answered with the item we hold, the way the
    ;; other ingesters answer it. It used to throw, which told the caller
    ;; nothing it could use and left the contexts it asked for nowhere to go.
    (if (:id existing-item)
      (assoc (datastore/get-item db existing-item) :previously-existing-item? true)
      (let [title (-> url
                      extract-title
                      (str/replace (str podcast-title ":") "")
                      str/trim)]
        (common/insert-item
          db
          title
          ""
          (conj context-ids-set podcast-id podcast-episodes-id apple-podcasts-platform-id)
          {:apple-podcast-episode url})))))
(ns repository.insertion.substack-note
  (:require [clojure.string :as str]
            [cambium.core :as log]
            [et.vp.ds :as datastore]
            [repository.insertion.common :as common]
            scrapers.substack-note
            utils
            upload))

(defn- get-author-id
  [db author-url substack-platform-id]
  (let [substack (common/insert-item db
                                     (subs author-url (inc (str/last-index-of author-url "/@")))
                                     ""
                                     #{substack-platform-id}
                                     {:substack-author author-url})]
    (:id substack)))

(defn- create-or-take-author-id
  [db author-url substack-platform-id]
  (let [author-id (:id (datastore/get-item-by-path db
                                                   "data->'resource-links'->>'substack-author'"
                                                   author-url))
        author-id (or author-id (get-author-id db author-url substack-platform-id))]
    author-id))

(defn match? [title] (re-matches #"https://substack.com\/@.*\/note/.*" title))

(defn ingest
  [db url context-ids-set]
  (let [substack-platform-id (common/get-item-or-throw-error db "Substack")
        poasts-id (common/get-item-or-throw-error db "Poasts")
        author-url (subs url 0 (str/index-of url "/note/"))
        author-id (create-or-take-author-id db author-url substack-platform-id)
        note-id (subs url 0 (or (str/index-of url "?") (count url)))
        tree (scrapers.substack-note/get-tree note-id)
        {:keys [title date year image description]} (scrapers.substack-note/get-date tree)
        year-id (common/get-item-or-throw-error db year)]
    (when (:id (datastore/get-item-by-path db "data->'resource-links'->>'substack-note'" note-id))
      (throw (Exception. "substack note already exists!")))
    (let [item (common/insert-item
                 db
                 title
                 ""
                 (conj context-ids-set poasts-id substack-platform-id author-id year-id)
                 {:substack-note note-id})
          item (datastore/update-context-description db (assoc item :description description))]
      (when image
        (try (upload/upload-preview-file db {:tempfile image} (:id item) "false")
             (catch Exception e
               (log/error
                 (str "problem while trying to create preview image for substack note. message"
                      (.getMessage e))))))
      (datastore/insert-date db (:id item) date)
      item)))
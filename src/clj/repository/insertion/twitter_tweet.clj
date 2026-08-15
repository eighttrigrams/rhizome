(ns repository.insertion.twitter-tweet
  (:require [clojure.string :as str]
            [et.vp.ds :as datastore]
            [repository.insertion.common :as common]
            utils))

(defn match? [title] (re-matches #"https://x.com\/.*\/status/.*" title))

(defn- get-handle-id
  [db handle-url twitter-platform-id twitter-handles-id]
  (let [twitter (common/insert-item db
                                    (str/replace handle-url "https://x.com/" "")
                                    (str "X@" (str/replace handle-url "https://x.com/" ""))
                                    #{twitter-platform-id twitter-handles-id}
                                    {:x-handle handle-url})]
    (:id twitter)))

(defn- create-or-take-handle-id
  [db handle-url twitter-platform-id twitter-handles-id]
  (let [handle-id
          (:id (datastore/get-item-by-path db "data->'resource-links'->>'x-handle'" handle-url))
        handle-id (or handle-id
                      (get-handle-id db handle-url twitter-platform-id twitter-handles-id))]
    handle-id))

(defn ingest
  [db url context-ids-set]
  (let [twitter-platform-id (common/get-item-or-throw-error db "Twitter")
        twitter-handles-id (common/get-item-or-throw-error db "Twitter Handles")
        poasts-id (common/get-item-or-throw-error db "Poasts")
        handle-url (subs url 0 (str/index-of url "/status/"))
        handle-id (create-or-take-handle-id db handle-url twitter-platform-id twitter-handles-id)
        note-id (subs url 0 (or (str/index-of url "?") (count url)))
        existing-item (datastore/get-item-by-path db "data->'resource-links'->>'x-post'" note-id)]
    ;; A post we already hold is answered with the item we hold, the way the
    ;; other ingesters answer it. It used to throw, which told the caller
    ;; nothing it could use and left the contexts it asked for nowhere to go.
    (if (:id existing-item)
      (assoc (datastore/get-item db existing-item) :previously-existing-item? true)
      (common/insert-item db
                          "X Post"
                          ""
                          (conj context-ids-set poasts-id twitter-platform-id handle-id)
                          {:x-post note-id}))))

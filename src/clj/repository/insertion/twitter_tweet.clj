(ns repository.insertion.twitter-tweet
  (:require [clojure.string :as str]
            datastore
            [hickory.select :as select]
            [datastore.get-item :as get-item]
            [repository.insertion.common :as common]
            utils
            [utils.url :as url]))

(defn match? [title]
  (re-matches #"https://x.com\/.*\/status/.*" title))

(defn ingest [db url context-ids-set _]
  (let [twitter-platform-id (common/get-item-or-throw-error db "Twitter")
        poasts-id          (common/get-item-or-throw-error db "Poasts")
        note-id (subs url 0 (or (str/index-of url "?")
                                (count url)))]
    (when (:id (get-item/get-item-by-path db "data->'resource-links'->>'x-post'" note-id))
      (throw (Exception. "x post already exists!")))
    (common/insert-item db 
                        "X Post" 
                        "" 
                        (conj context-ids-set 
                              poasts-id
                              twitter-platform-id) 
                        {:x-post note-id})))
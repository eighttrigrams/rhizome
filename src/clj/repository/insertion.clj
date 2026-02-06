(ns repository.insertion
  (:require [cambium.core :as log]
            [et.vp.ds :as datastore]
            [repository.insertion.substack :as substack]
            [repository.insertion.substack-note :as substack-note]
            [repository.insertion.twitter-tweet :as twitter-tweet]
            [repository.insertion.apple-pods :as apple-pods]
            [repository.insertion.substack-external :as substack-external]
            [repository.insertion.substack-plain :as substack-plain]
            [repository.insertion.youtube :as youtube]
            [repository.insertion.github :as github]
            #_[repository.insertion.file :as file]
            [repository.insertion.batch :as batch]
            [repository.insertion.website :as website]))

(defn- normal-item-insertion
  [db title context-ids-set]
  (datastore/new-item db title "" context-ids-set nil))

(defn insert-item
  [db title selected-item selected-secondary-contexts-set]
  (log/info (str "Import for " title))
  (let [context-ids-set (into #{} (conj selected-secondary-contexts-set (:id selected-item)))]
    (cond (batch/match? title) (batch/ingest db title nil nil)
          (youtube/match? title) (youtube/ingest db title context-ids-set nil)
          (github/match? title) (github/save-article db title context-ids-set)
          (apple-pods/match? title) (apple-pods/ingest db title context-ids-set nil)
          (substack/match? title) ((substack/make:save-article false) db title context-ids-set)
          (substack-external/match? title)
            (substack-external/save-article db title context-ids-set)
          (substack-plain/match? title) (substack-plain/save-article db title context-ids-set)
          (substack-note/match? title) (substack-note/ingest db title context-ids-set)
          (twitter-tweet/match? title) (twitter-tweet/ingest db title context-ids-set nil)
          (website/match? title) (website/ingest db title context-ids-set nil)
          :else (normal-item-insertion db title context-ids-set))))

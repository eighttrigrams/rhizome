(ns repository.insertion
  (:require [cambium.core :as log]
            [clojure.string :as str]
            datastore
            [repository.insertion.substack :as substack]
            [repository.insertion.substack-note :as substack-note]
            [repository.insertion.twitter-tweet :as twitter-tweet]
            [repository.insertion.apple-pods :as apple-pods]
            [repository.insertion.substack-external :as substack-external]
            [repository.insertion.youtube :as youtube]
            #_[repository.insertion.file :as file]
            [repository.insertion.unz :as unz]
            [repository.insertion.takimag :as takimag]
            [repository.insertion.batch :as batch]))

(defn- normal-issue-insertion 
  [db 
   title 
   context-ids-set
   split-short-title?]
  (let [parts           (if split-short-title? (str/split title #"\|") (list title))
        title           (if (= 1 (count parts)) 
                          (first parts) 
                          (second parts))
        short-title     (if (= 1 (count parts))
                          ""
                          (first parts))] 
    (datastore/new-issue db 
                         title
                         short-title
                         context-ids-set
                         {:suppress-digit-check? true})))

(defn insert-issue 
  [db 
   title 
   selected-context
   selected-secondary-contexts-set
   alternative-behaviour?]
  (log/info (str "Import for " title))
  (let [context-ids-set (into #{} (conj selected-secondary-contexts-set (:id selected-context)))]
    (cond (batch/match? title)
          (batch/ingest db nil nil nil)
          ;; not supporting that any longer, since that doesn't guarantee the file is imported properly
          #_(file/match? title)
          #_(file/ingest db title context-ids-set nil)
          (youtube/match? title) 
          (youtube/ingest db title context-ids-set nil)
          (apple-pods/match? title) 
          (apple-pods/ingest db title context-ids-set nil)
          (substack/match? title)
          ((substack/make:save-article false) db title context-ids-set alternative-behaviour?)
          (substack-external/match? title)
          (substack-external/save-article db title context-ids-set alternative-behaviour?)
          (substack-note/match? title)
          (substack-note/ingest db title context-ids-set alternative-behaviour?)
          (twitter-tweet/match? title)
          (twitter-tweet/ingest db title context-ids-set alternative-behaviour?)
          (unz/match? title) 
          (unz/ingest db title context-ids-set alternative-behaviour?) 
          (takimag/match? title) 
          (takimag/ingest db title context-ids-set alternative-behaviour?) 
          :else 
          (normal-issue-insertion db title context-ids-set alternative-behaviour?))))

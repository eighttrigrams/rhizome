(ns repository.insertion
  (:require [cambium.core :as log]
            [clojure.string :as str]
            datastore
            [repository.insertion.substack :as substack]
            [repository.insertion.substack-external :as substack-external]
            [repository.insertion.youtube :as youtube]
            [repository.insertion.file :as file]
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
                         context-ids-set)))

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
          (youtube/match? title) 
          (youtube/ingest db title context-ids-set nil) 
          (substack/match? title)
          ((substack/make:save-article false) db title context-ids-set alternative-behaviour?)
          (substack-external/match? title)
          (substack-external/save-article db title context-ids-set alternative-behaviour?)
          (file/match? title)
          (file/ingest db title context-ids-set nil)
          :else 
          (normal-issue-insertion db title context-ids-set alternative-behaviour?))))

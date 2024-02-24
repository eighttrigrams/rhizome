(ns repository.insertion
  (:require [clojure.string :as str]
            datastore
            [repository.homefolder :as home]
            [repository.insertion.substack :as substack]
            [repository.insertion.youtube :as youtube]
            [repository.insertion.homefolder :as homefolder]))

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
                          (first parts))
        _selected-issue (datastore/new-issue db 
                                             title
                                             short-title
                                             context-ids-set)]))

;; TODO i could use multimethods
(defn insert-issue 
  [db 
   title 
   selected-context
   selected-secondary-contexts-set
   alternative-behaviour?]
  (let [context-ids-set (into #{} (conj selected-secondary-contexts-set (:id selected-context)))]
    (cond (= "IMPORT" title)
          (homefolder/batch-insertion db)
          (re-matches #"https://www.youtube.com/watch\?v=[.[^&]]*" title) 
          (youtube/save-video db 
                              title 
                              context-ids-set) 
          (re-matches #"https://.*\.substack.com\/p\/.*" title)
          (substack/save-article db 
                                 title 
                                 context-ids-set) 
          (home/supported-file-type? title)
          (homefolder/save-file db 
                                title 
                                context-ids-set)
          :else 
          (normal-issue-insertion db 
                                  title 
                                  context-ids-set
                                  alternative-behaviour?))))

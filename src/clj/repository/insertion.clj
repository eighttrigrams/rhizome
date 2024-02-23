(ns repository.insertion
  (:require [clojure.string :as str]
            datastore
            [repository.insertion.substack :as substack]
            [repository.insertion.youtube :as youtube]))

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

(defn insert-issue 
  [db 
   title 
   selected-context 
   selected-secondary-contexts-set
   split-short-title?]
  (let [selected-context-id (:id selected-context)]
    (cond (re-matches #"https://www.youtube.com/watch\?v=[.[^&]]*" title) 
          (youtube/save-video db title selected-context-id) 
          (re-matches #"https://.*\.substack.com\/p\/.*" title)
          (substack/save-article db title selected-context-id) 
          :else 
          (normal-issue-insertion db title selected-context-id selected-secondary-contexts-set split-short-title?))))

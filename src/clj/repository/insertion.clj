(ns repository.insertion
  (:require [clojure.string :as str]
            datastore
            [repository.insertion.substack :as substack]
            [repository.insertion.youtube :as youtube]
            [repository.insertion.homefolder :as homefolder]))

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

;; TODO i could use multimethods
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
          (or (str/ends-with? (str/lower-case title) ".mp4")
              (str/ends-with? (str/lower-case title) ".mp3")
              (str/ends-with? (str/lower-case title) ".pdf")
              (str/ends-with? (str/lower-case title) ".jpeg")
              (str/ends-with? (str/lower-case title) ".jpg")
              (str/ends-with? (str/lower-case title) ".png")
              )
          (homefolder/save-file db title selected-context-id)
          :else 
          (normal-issue-insertion db title selected-context-id selected-secondary-contexts-set split-short-title?))))

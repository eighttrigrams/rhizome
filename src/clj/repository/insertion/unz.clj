(ns repository.insertion.unz
  (:require [et.vp.ds :as datastore]
            [hickory.select :as select]
            [repository.insertion.common :as common]
            [repository.chatgpt :as chatgpt]
            utils
            scrapers.common))

(defn- extract-content [hickory-tree]
  (:content (first (drop 2 (:content (first (select/select
                                             (select/and 
                                              (select/tag "div")
                                              (select/id "contents-holder")) 
                                             hickory-tree)))))))

(defn match? [title]
  (re-matches #"https://www.unz.com.*" title))

(defn ingest [db url context-ids-set]
  (let [articles-id     (common/get-item-or-throw-error db "Articles")
        [title content] (scrapers.common/get-post url extract-content)] 
    (common/insert-item db 
                        title 
                        "" 
                        (conj context-ids-set 
                              articles-id) 
                        {:web-article url})))
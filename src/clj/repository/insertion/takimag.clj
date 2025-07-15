(ns repository.insertion.takimag 
  (:require [hickory.select :as select]
            [repository.insertion.common :as common]
            scrapers.common
            utils))

(defn- extract-content [hickory-tree]
  (let [content (:content (first (select/select
                                  (select/and 
                                   (select/tag "div")
                                   (select/id "post")) 
                                  hickory-tree)))]
    content))

(defn match? [title]
  (re-matches #"https://www.takimag.com.*" title))

(defn ingest [db url context-ids-set]
  (let [articles-id     (common/get-item-or-throw-error db "Articles")
        [title] (scrapers.common/get-post url extract-content)] (common/insert-item db 
                                   title 
                                   "" 
                                   (conj context-ids-set 
                                         articles-id) 
                                   {:web-article url})))

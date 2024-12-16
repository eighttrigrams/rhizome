(ns repository.insertion.takimag 
  (:require [et.vp.ds :as datastore]
            [hickory.select :as select]
            [repository.insertion.common :as common]
            [repository.chatgpt :as chatgpt]
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

(defn ingest [db url context-ids-set should-capture-summary?]
  (let [articles-id     (common/get-item-or-throw-error db "Articles")
        [title content] (scrapers.common/get-post url extract-content)
        summary              (and should-capture-summary?
                                  (chatgpt/get-summary content))
        issue                (common/insert-item db 
                                                 title 
                                                 "" 
                                                 (conj context-ids-set 
                                                       articles-id) 
                                                 {:web-article url})]
    (when (and issue summary)
      (datastore/update-context-description db (assoc issue :description 
                                                      (utils/wrap-summary summary))))))

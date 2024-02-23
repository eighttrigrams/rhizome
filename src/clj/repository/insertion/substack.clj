(ns repository.insertion.substack
  (:require [clojure.string :as str]
            datastore
            [clj-http.client :as http]
            [hickory.core :as html]
            [hickory.select :as select]
            [datastore.get-item :as get-item]))

(defn- get-post-title [url]
  (let [tree (html/as-hickory (html/parse (:body (http/get url))))
        title (first (:content (first (select/select (select/and (select/tag "h1")
                                                   (select/class "post-title")) tree))))]
    title))

(defn- get-substack-id 
  [db 
   [subdomain-url 
    subdomain-handle 
    subdomain-full-url] 
   substacks-id]
  (let [substack (datastore/new-issue db 
                                      subdomain-url
                                      subdomain-handle
                                      substacks-id
                                      #{})
        substack (datastore/update-issue db
                                         {:issue              (update substack :data
                                                                      (fn [data] (assoc data :resource-links {:substack subdomain-full-url})))
                                          :related-issues-ids '()})]
    (:id (datastore/upgrade-issue-to-context! db substack))))

(defn-  convert [url]
  (let [idx (str/index-of url ".substack")
          su (subs url 0 idx)
          subdomain (str/replace su "https://" "")
          subdomain-handle (str subdomain ".substack")
          subdomain-url (str subdomain-handle ".com")
          subdomain-full-url (str "https://" subdomain-url)]
    [subdomain-handle
     subdomain-url
     subdomain-full-url]))

(defn save-article [db url selected-context-id]
  (let [substacks-id (:id (get-item/get-item-by-title db {:title "Substacks"}))
        articles-id (:id (get-item/get-item-by-title db {:title "Articles"}))]
    (when-not substacks-id (throw (Exception. "no substacks-id")))
    (when-not articles-id (throw (Exception. "no articles-id")))
    (let [identifiers (convert url)
          substack-id (:id (get-item/get-item-by-substack db (last identifiers)))
          substack-id (or substack-id (get-substack-id db identifiers substacks-id))
          title       (get-post-title url)
          _           (when-not title (throw (Exception. "no post title")))
          issue       (datastore/new-issue db 
                                           (get-post-title url)
                                           ""
                                           selected-context-id
                                           #{substack-id articles-id})]
      (datastore/update-issue db {:issue              (update issue :data 
                                                              (fn [data] (assoc data :resource-links {:substack-article url})))
                                  :related-issues-ids '()}))))

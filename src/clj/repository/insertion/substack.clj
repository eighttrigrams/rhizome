(ns repository.insertion.substack
  (:require [clojure.string :as str]
            datastore
            [clj-http.client :as http]
            [hickory.core :as html]
            [hickory.select :as select]
            [datastore.get-item :as get-item]
            [repository.insertion.common :as common]))

(defn- get-post-title [url]
  (let [tree (html/as-hickory (html/parse (:body (http/get url))))
        title (first (:content (first (select/select (select/and (select/tag "h1")
                                                   (select/class "post-title")) tree))))]
    title))

(defn- get-substack-id 
  [db 
   [subdomain-handle
    subdomain-url 
    subdomain-full-url] 
   substack-platform-id
   substacks-id]
  (let [substack (common/insert-item db 
                                     subdomain-url
                                     subdomain-handle 
                                     #{substack-platform-id substacks-id}
                                     {:substack subdomain-full-url})]
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

(defn- insert-article 
  [db 
   url 
   context-ids-set 
   substack-platform-id 
   substack-id 
   articles-id]
  (common/insert-item db 
                      (get-post-title url) 
                      "" 
                      (conj context-ids-set substack-id articles-id substack-platform-id) 
                      {:substack-article url}))

(defn- create-or-take-substack-id [db identifiers substack-platform-id substacks-id]
  (let [substack-id (:id (get-item/get-item-by-path db 
                                                    "data->'resource-links'->>'substack'" 
                                                    (last identifiers)))
        substack-id (or substack-id (get-substack-id db 
                                                     identifiers 
                                                     substack-platform-id
                                                     substacks-id))]
    substack-id))

(defn save-article [db url context-ids-set]
  (let [substack-platform-id (:id (get-item/get-item-by-title db {:title "Substack"}))
        substacks-id (:id (get-item/get-item-by-title db {:title "Substacks"}))
        articles-id (:id (get-item/get-item-by-title db {:title "Articles"}))]
    (when-not substacks-id (throw (Exception. "no substack-platform-id")))
    (when-not substacks-id (throw (Exception. "no substacks-id")))
    (when-not articles-id (throw (Exception. "no articles-id")))
    (let [identifiers (convert url)
          title       (get-post-title url)
          _           (when-not (seq title) (throw (Exception. "no post title")))
          substack-id (create-or-take-substack-id db identifiers substack-platform-id substacks-id)
          _ (when (:id (get-item/get-item-by-path db 
                                                  "data->'resource-links'->>'substack-article'" 
                                                  url))
              (throw (Exception. "substack article already exists!")))]
      (insert-article db 
                      url 
                      context-ids-set
                      substack-platform-id
                      substack-id
                      articles-id))))

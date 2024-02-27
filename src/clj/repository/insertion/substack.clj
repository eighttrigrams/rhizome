(ns repository.insertion.substack
  (:require [clojure.string :as str]
            datastore
            [hickory.select :as select]
            [datastore.get-item :as get-item]
            [repository.insertion.common :as common]
            [repository.chatgpt :as chatgpt]
            utils
            [utils.url :as url]))

(defn- get-substack-id 
  [db 
   [subdomain-handle
    subdomain-url 
    subdomain-full-url] 
   substack-platform-id
   substacks-id]
  (let [substack (common/insert-item db 
                                     subdomain-url
                                     (if (boolean (re-find #"\d" subdomain-handle))
                                       ""
                                       subdomain-handle) 
                                     #{substack-platform-id substacks-id}
                                     {:substack subdomain-full-url})]
    (:id (datastore/upgrade-issue-to-context! db substack))))

(defn-  convert [url]
  (let [subdomain (url/get-subdomain url)
        subdomain-handle (str subdomain ".substack")
        subdomain-url (str subdomain-handle ".com")
        subdomain-full-url (str "https://" subdomain-url)]
    [;; short-title
     subdomain-handle
     ;; title
     subdomain-url
     ;; link
     subdomain-full-url]))

(defn- convert-external [url]
  (let [without-protocol (str/replace url "https://" "")
        only-domain (subs without-protocol 0 
                          (str/index-of without-protocol "/"))]
    [only-domain
     only-domain
     (str "https://" only-domain)]))

(defn- create-or-take-substack-id [db 
                                   identifiers 
                                   substack-platform-id 
                                   substacks-id]
  (let [substack-id (:id (get-item/get-item-by-path db 
                                                    "data->'resource-links'->>'substack'" 
                                                    (last identifiers)))
        substack-id (or substack-id (get-substack-id db 
                                                     identifiers 
                                                     substack-platform-id
                                                     substacks-id))]
    substack-id))



(defn- validate-preconditions [db url title]
  (let [_ (when-not (seq title) (throw (Exception. "no post title")))
        _ (when (:id (get-item/get-item-by-path db 
                                                "data->'resource-links'->>'substack-article'" 
                                                url))
            (throw (Exception. "substack article already exists!")))]))

(defn match? [title]
  (re-matches #"https://.*\.substack.com\/p\/.*" title))

(defn- extract-content [hickory-tree]
  (:content (first (:content (first (select/select
     (select/and (select/tag "div")
                 (select/class "available-content")) hickory-tree))))))

(defn make:save-article [external?]
  (fn save-article [db url context-ids-set should-capture-summary?]
    (let [url                  (url/url-without-query-params url)
          substack-platform-id (common/get-item-or-throw-error db "Substack")
          substacks-id         (common/get-item-or-throw-error db "Substacks")
          articles-id          (common/get-item-or-throw-error db "Articles")
          [title content]      (utils/get-post url extract-content)
          _                    (validate-preconditions db url title)
          summary              (and should-capture-summary?
                                    (chatgpt/get-summary content))
          substack-id          (create-or-take-substack-id 
                                db 
                                (if external?
                                  (convert-external url)
                                  (convert url))
                                substack-platform-id
                                substacks-id)
          issue                (common/insert-item db 
                                                   title 
                                                   "" 
                                                   (conj context-ids-set 
                                                         (or substack-id articles-id) ;; hack 
                                                         articles-id 
                                                         substack-platform-id) 
                                                   {:substack-article url})]
      (when (and issue summary)
        (datastore/update-issue-description db (assoc issue :description 
                                                      (utils/wrap-summary summary)))))))

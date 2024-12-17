(ns repository.insertion.substack
  (:require [clojure.string :as str]
            [et.vp.ds :as datastore]
            [cambium.core :as log]
            [repository.insertion.common :as common]
            [repository.chatgpt :as chatgpt]
            utils
            [utils.url :as url]
            [scrapers.substack :as substack]
            upload))

(defn match? [title]
  (re-matches #"https://.*\.substack.com\/p\/.*" title))

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
    (:id substack)))

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
  (let [substack-id (:id (datastore/get-item-by-path db 
                                                    "data->'resource-links'->>'substack'" 
                                                    (last identifiers)))
        substack-id (or substack-id (get-substack-id db 
                                                     identifiers 
                                                     substack-platform-id
                                                     substacks-id))]
    substack-id))

(defn make:save-article [external?]
  (fn save-article [db url context-ids-set should-capture-summary?]
    (let [url                  (url/url-without-query-params url)
          substack-platform-id (common/get-item-or-throw-error db "Substack")
          substacks-id         (common/get-item-or-throw-error db "Substacks")
          articles-id          (common/get-item-or-throw-error db "Articles")
          {:keys [title content date year image]}
          ,,(substack/get-post url substack/extract-content)
          year-id              (common/get-item-or-throw-error db year)
          summary              (and should-capture-summary?
                                    (chatgpt/get-summary content))
          substack-id          (create-or-take-substack-id 
                                db 
                                (if external?
                                  (convert-external url)
                                  (convert url))
                                substack-platform-id
                                substacks-id)
          context-ids-set     (conj context-ids-set
                                    (or substack-id articles-id) ;; hack 
                                    articles-id 
                                    substack-platform-id
                                    year-id)
          existing-item       (datastore/get-item-by-path db 
                                                          "data->'resource-links'->>'substack-article'" 
                                                          url)]
      
      (if (:id existing-item)
        (assoc (datastore/get-item db existing-item) :previously-existing-item? true)
        (let [item  (common/insert-item db 
                                                  title 
                                                  "" 
                                                  context-ids-set 
                                                  {:substack-article url})]
          (datastore/insert-date db (:id item) date true)
          (log/info (str "created new item" item))
          (when image (try (upload/upload-preview-file db {:tempfile image} (:id item) "false")
                           (catch Exception e
                             (log/error (str "problem while trying to create preview image for substack article. message" (.getMessage e))))))
          (if (and item summary)
            (datastore/update-item db (assoc item :description 
                                             (utils/wrap-summary summary)))
            item))))))

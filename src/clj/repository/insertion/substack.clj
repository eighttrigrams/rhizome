(ns repository.insertion.substack
  (:require [clojure.string :as str]
            [et.personalist :as datastore]
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

(defn- validate-preconditions [db url title]
  (let [_ (when-not (seq title) (throw (Exception. "no post title")))
        _ (when (:id (datastore/get-item-by-path db 
                                                "data->'resource-links'->>'substack-article'" 
                                                url))
            (throw (Exception. "substack article already exists!")))]))

(defn make:save-article [external?]
  (fn save-article [db url context-ids-set should-capture-summary?]
    (let [url                  (url/url-without-query-params url)
          substack-platform-id (common/get-item-or-throw-error db "Substack")
          substacks-id         (common/get-item-or-throw-error db "Substacks")
          articles-id          (common/get-item-or-throw-error db "Articles")
          {:keys [title content date year image]}
          ,,(substack/get-post url substack/extract-content)
          year-id              (common/get-item-or-throw-error db year)
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
          context-ids-set     (conj context-ids-set
                                    (or substack-id articles-id) ;; hack 
                                    articles-id 
                                    substack-platform-id
                                    year-id)
          _ (log/info         (str "context-ids-set: " context-ids-set))
          item                (common/insert-item db 
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
        item))))

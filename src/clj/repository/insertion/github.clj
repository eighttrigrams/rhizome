(ns repository.insertion.github
  (:require [clojure.string :as str]
            [et.vp.ds :as datastore]
            [cambium.core :as log]
            [repository.insertion.common :as common]
            utils
            [utils.url :as url]
            upload))

(defn match? [title]
  (re-matches #"https://.*\.github.com\/.*/.*" title))

(defn- get-substack-id 
  [db 
   [subdomain-handle
    subdomain-url 
    subdomain-full-url] 
   github-platform-id
   github-orgs-id]
  (let [substack (common/insert-item db 
                                     subdomain-url
                                     subdomain-handle 
                                     #{github-platform-id github-orgs-id}
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

(defn- create-or-take-github-org-id [db 
                                     identifiers 
                                     github-platform-id 
                                     github-orgs-id]
  (let [substack-id (:id (datastore/get-item-by-path db 
                                                     "data->'resource-links'->>'github-org'" 
                                                     (last identifiers)))
        substack-id (or substack-id (get-substack-id db 
                                                     identifiers 
                                                     github-platform-id
                                                     github-orgs-id))]
    substack-id))

(defn- validate-preconditions [db url title]
  (let [_ (when-not (seq title) (throw (Exception. "no post title")))
        _ (when (:id (datastore/get-item-by-path db 
                                                "data->'resource-links'->>'github-repo'" 
                                                url))
            (throw (Exception. "github repo already exists!")))]))

(defn make:save-article [external?]
  (fn save-article [db url context-ids-set]
    (let [url                  (url/url-without-query-params url)
          github-platform-id (common/get-item-or-throw-error db "GitHub")
          github-org-id      (common/get-item-or-throw-error db "GitHub Organisation")
          libraries-id       (common/get-item-or-throw-error db "Library") ;; TODO should be repository, probably
        ;;   {:keys [title content date year image]}
        ;;   ,,(substack/get-post url substack/extract-content)
        ;;   year-id              (common/get-item-or-throw-error db year)
          _                    (validate-preconditions db url "TODO")
        ;;   summary              (and should-capture-summary?
                                    ;; (chatgpt/get-summary content))
          substack-id          (create-or-take-github-org-id 
                                db 
                                (if external?
                                  (convert-external url)
                                  (convert url))
                                github-platform-id
                                github-org-id)
          context-ids-set     (conj context-ids-set
                                    (or substack-id libraries-id) ;; hack 
                                    libraries-id 
                                    github-platform-id)
          _ (log/info         (str "context-ids-set: " context-ids-set))
          item                (common/insert-item db 
                                                  "abc" 
                                                  "" 
                                                  context-ids-set 
                                                  {:github-repo url})]
      (log/info (str "created new item" item))
      item)))

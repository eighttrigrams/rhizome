(ns repository.insertion.substack
  (:require [clojure.string :as str]
            datastore
            [clj-http.client :as http]
            [hickory.core :as html]
            [hickory.select :as select]
            [datastore.get-item :as get-item]
            [repository.insertion.common :as common]
            [repository.chatgpt :as chatgpt]))

(defn extract-text [content]
  (str/join (doall (reduce (fn [acc val]
                             (cond (string? val)
                                   (concat acc [val])
                                   (and (= :element (:type val)) 
                                        (:content val))
                                   (concat acc (extract-text (:content val)))
                                   :else acc))
                           [] 
                           content))))

(defn- get-post [url]
  (let [tree (html/as-hickory (html/parse (:body (http/get url))))
        title (first (:content 
                      (first 
                       (select/select
                        (select/tag "title")
                        tree))))
        content (:content (first (:content (first (select/select
                                                          (select/and 
                                                           (select/tag "div")
                                                           (select/class "available-content")) tree)))))]
    [title (extract-text content)]))

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
  (let [idx (str/index-of url ".substack")
          su (subs url 0 idx)
          subdomain (str/replace su "https://" "")
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

(defn wrap-summary [summary]
  (str "--- ChatGPT | " 
       (:model (chatgpt/configuration))
       " | BEGIN ---\n\n" 
       summary
       "\n\n--- ChatGPT | "
       (:model (chatgpt/configuration))
       " | END ---"))

(defn- validate-preconditions [db url title]
  (let [_ (when-not (seq title) (throw (Exception. "no post title")))
        _ (when (:id (get-item/get-item-by-path db 
                                                "data->'resource-links'->>'substack-article'" 
                                                url))
            (throw (Exception. "substack article already exists!")))]))

(defn match? [title]
  (re-matches #"https://.*\.substack.com\/p\/.*" title))

(defn make:save-article [external?]
  (fn save-article [db url context-ids-set should-capture-summary?]
    (let [substack-platform-id (common/get-item-or-throw-error db "Substack")
          substacks-id         (common/get-item-or-throw-error db "Substacks")
          articles-id          (common/get-item-or-throw-error db "Articles")
          [title content]      (get-post url)
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
                                                      (wrap-summary summary)))))))

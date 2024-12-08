(ns repository.insertion.substack
  (:require [clojure.string :as str]
            datastore
            [cambium.core :as log]
            [hickory.select :as select]
            [hickory.core :as html]
            [clj-http.client :as http]
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

(defn match? [title]
  (re-matches #"https://.*\.substack.com\/p\/.*" title))

(defn- extract-content [hickory-tree]
  (:content (first (:content (first (select/select
     (select/and (select/tag "div")
                 (select/class "available-content")) hickory-tree))))))

(defn- get-property [tree name]
   (-> (select/select (select/attr "property" (fn [x] (= x name))) tree)
       first
       :attrs
       :content
       str/trim))

(defn- convert-month [month]
  (get {"Jan" "01"
        "Feb" "02"
        "Mar" "03"
        "Apr" "04"
        "May" "05"
        "Jun" "06"
        "Jul" "07"
        "Aug" "08"
        "Sep" "09"
        "Oct" "10"
        "Nov" "11"
        "Dec" "12"} month))

(defn- convert-date [date]
  (let [[month day year] (filter #(not-empty %) (str/split date #"[\s,]"))]
    [(str year  "-" (convert-month month) "-" day) year]))

(defn- extract-date [tree]
   (let [base (select/select (select/descendant (select/class "post-header")
                                                (select/tag "div")) tree)]
     (doall (->> base
                 (filter (fn [item] (string? (first (:content item)))))
                 (map (fn [item] (first (:content item))))
                 (filter (fn [item] (re-matches #"[A-Z][a-z]{2,4}\s\d\d,\s\d\d\d\d" item)))
                 first))))

(defn get-post [url extract-content]
  (let [tree (html/as-hickory (html/parse (:body (http/get url))))
        title (get-property tree "og:title")
        subtitle (get-property tree "og:description")
        date (-> tree extract-date convert-date)]
    [(str title " - " subtitle) 
     date
     (-> tree 
         extract-content  
         utils/extract-text)]))

(comment
  (get-post "https://woodfromeden.substack.com/p/the-anti-autism-manifesto" extract-content))

(defn make:save-article [external?]
  (fn save-article [db url context-ids-set should-capture-summary?]
    (let [url                  (url/url-without-query-params url)
          substack-platform-id (common/get-item-or-throw-error db "Substack")
          substacks-id         (common/get-item-or-throw-error db "Substacks")
          articles-id          (common/get-item-or-throw-error db "Articles")
          [title [date year] content]      (get-post url extract-content)
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
          _ (log/info (str "context-ids-set: " context-ids-set))
          item                (common/insert-item db 
                                                  title 
                                                  "" 
                                                  context-ids-set 
                                                  {:substack-article url})
          item                (datastore/insert-date db (:id item) date true)]
      (log/info (str "created new item" item))
      (if (and item summary)
        (datastore/update-item db (assoc item :description 
                                         (utils/wrap-summary summary)))
        item))))

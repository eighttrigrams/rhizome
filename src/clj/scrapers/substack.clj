(ns scrapers.substack
  (:require utils
            [clojure.string :as str]
            [hickory.select :as select]
            [hickory.core :as html]
            [clj-http.client :as http]
            [cheshire.core :as json]
            scrapers.common
            scrapers.substack.common))

(defn extract-content
  [hickory-tree]
  (:content (first (:content (first (select/select (select/and (select/tag "div")
                                                               (select/class "available-content"))
                                                   hickory-tree))))))

(defn- convert-date
  [date]
  (let [[month day year] (filter #(not-empty %) (str/split date #"[\s,]"))]
    [(str year "-" (scrapers.substack.common/convert-month month) "-" day) year]))

(defn- extract-date
  [tree]
  (let [base (select/select (select/descendant (select/class "post-header") (select/tag "div"))
                            tree)]
    (doall (->> base
                (filter (fn [item] (string? (first (:content item)))))
                (map (fn [item] (first (:content item))))
                (filter (fn [item] (re-matches #"[A-Z][a-z]{2,4}\s\d\d,\s\d\d\d\d" item)))
                first))))

(defn- extract-date-for-pods
  [tree]
  (let [base (select/select (select/descendant ;; difference is I don't filter for post-header
                              (select/tag "div"))
                            tree)]
    (doall (->> base
                (filter (fn [item] (string? (first (:content item)))))
                (map (fn [item] (first (:content item))))
                (filter (fn [item] (re-matches #"[A-Z][a-z]{2,4}\s\d\d,\s\d\d\d\d" item)))
                first))))

(defn- extract-date-from-json-ld
  [tree]
  (try (let [scripts (select/select (select/and (select/tag "script")
                                                (select/attr "type" #(= % "application/ld+json")))
                                    tree)
             json-str (->> scripts
                           (map #(first (:content %)))
                           (filter some?)
                           first)]
         (when json-str
           (let [data (json/parse-string json-str true)
                 date-published (:datePublished data)]
             (when date-published
               (let [[date-part] (str/split date-published #"T")
                     [year month day] (str/split date-part #"-")]
                 {:date (str year "-" month "-" day) :year year})))))
       (catch Exception _ nil)))

(defn podcast-episode?
  [tree]
  (and (extract-date-for-pods tree)
       (not (extract-date tree))
       (try (scrapers.common/get-name tree "twitter:player") true (catch Exception _e false))))

(defn get-post
  [url extract-content]
  (let [tree (html/as-hickory (html/parse (:body (http/get url))))
        title (scrapers.common/get-property tree "og:title")
        subtitle (scrapers.common/get-property tree "og:description")
        image (scrapers.common/get-property tree "og:image")
        html-date (or (extract-date tree) (extract-date-for-pods tree))
        [date year] (if html-date
                      (convert-date html-date)
                      (let [{:keys [date year]} (extract-date-from-json-ld tree)] [date year]))]
    {:title (subs (str title " - " subtitle) 0 (min 255 (count (str title " - " subtitle))))
     :date date
     :year year
     :image (when image (:body (http/get image {:as :byte-array})))
     :content (-> tree
                  extract-content
                  scrapers.common/extract-text)
     :type (if (podcast-episode? tree) :podcast-episode :article)}))

(comment
  (def tree (html/as-hickory (html/parse (:body (http/get "")))))
  (extract-date-for-pods tree)
  (podcast-episode? tree)
  (:image (get-post "" extract-content))
  (def data (:body (http/get "" {:as :byte-array})))
  (require '[clojure.java.io :as io])
  (io/copy data (io/file "")))

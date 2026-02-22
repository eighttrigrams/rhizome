(ns scrapers.substack-note
  (:require utils
            [clojure.string :as str]
            [hickory.select :as select]
            [hickory.core :as html]
            [clj-http.client :as http]
            [cheshire.core :as json]
            scrapers.common
            scrapers.substack.common))

(defn get-tree [url] (html/as-hickory (html/parse (:body (http/get url)))))

(defn- get-date-from-title-attr
  [tree]
  (let [title-val (-> (select/select
                        (select/attr "title"
                                     (fn [x]
                                       (when (string? x)
                                         (re-matches #"[A-Z][a-z]{2,4}\s\d{1,2},\s\d\d\d\d.*" x))))
                        tree)
                      first
                      :attrs
                      :title)]
    (when title-val
      (let [[date-and-month year] (str/split title-val #",")
            year (str/trim year)
            [month day] (str/split date-and-month #"\s")
            month (scrapers.substack.common/convert-month month)
            day (format "%02d" (Integer/parseInt day))]
        {:date (str year "-" month "-" day) :year year}))))

(defn- get-json-ld
  [tree]
  (let [scripts (select/select (select/and (select/tag "script")
                                           (select/attr "type" #(= % "application/ld+json")))
                               tree)]
    (some (fn [script]
            (try (let [content (first (:content script))
                       parsed (json/parse-string content true)]
                   (when (:dateCreated parsed) parsed))
                 (catch Exception _ nil)))
          scripts)))

(defn- get-date-from-json-ld
  [tree]
  (when-let [ld (get-json-ld tree)]
    (let [date-str (:dateCreated ld)
          [date-part] (str/split date-str #"T")
          [year month day] (str/split date-part #"-")]
      {:date date-part :year year :month month :day day :text (:text ld)})))

(defn get-date
  [tree]
  (let [date-info (or (get-date-from-json-ld tree) (get-date-from-title-attr tree))
        _ (when-not date-info (throw (Exception. "Could not extract date from substack note")))
        description (let [og (scrapers.common/get-property tree "og:description")]
                      (if (str/blank? og) (or (:text date-info) "") og))
        title (subs description 0 (min 255 (count description)))
        image-url (scrapers.common/get-property tree "og:image")
        image (when-not (str/blank? image-url)
                (try (:body (http/get image-url {:as :byte-array})) (catch Exception _ nil)))]
    (merge date-info {:title title :description description :image image})))

(comment
  (def tree (html/as-hickory (html/parse (:body (http/get "")))))
  (get-date tree))

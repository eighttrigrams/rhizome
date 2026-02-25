(ns scrapers.simonwillison
  (:require [clojure.string :as str]
            [hickory.select :as select]
            [hickory.core :as html]
            [clj-http.client :as http]
            [scrapers.common :as common]))

(def ^:private month-map
  {"jan" "01"
   "feb" "02"
   "mar" "03"
   "apr" "04"
   "may" "05"
   "jun" "06"
   "jul" "07"
   "aug" "08"
   "sep" "09"
   "oct" "10"
   "nov" "11"
   "dec" "12"
   "january" "01"
   "february" "02"
   "march" "03"
   "april" "04"
   "june" "06"
   "july" "07"
   "august" "08"
   "september" "09"
   "october" "10"
   "november" "11"
   "december" "12"})

(defn- convert-month [m] (get month-map (str/lower-case m)))

(defn- pad [s] (if (= 1 (count s)) (str "0" s) s))

(defn- extract-date-from-url
  [url]
  (when-let [[_ year month day] (re-find #"simonwillison\.net/(\d{4})/([A-Za-z]{3})/(\d{1,2})/"
                                         url)]
    (when-let [mm (convert-month month)] {:date (str year "-" mm "-" (pad day)) :year year})))

(defn- extract-text-recursive
  [node]
  (cond (string? node) node
        (map? node) (str/join (map extract-text-recursive (:content node)))
        (sequential? node) (str/join (map extract-text-recursive node))
        :else ""))

(defn- extract-date-from-content
  [tree]
  (let [text (extract-text-recursive tree)]
    (when-let [[_ day month year]
                 (re-find #"(?:Posted|Created:?)\s+(\d{1,2})\w{0,2}\s+(\w+)\s+(\d{4})" text)]
      (when-let [mm (convert-month month)] {:date (str year "-" mm "-" (pad day)) :year year}))))

(defn get-post
  [url]
  (try (let [tree (html/as-hickory (html/parse (:body (http/get url
                                                                {:socket-timeout 10000
                                                                 :connection-timeout 5000}))))
             og-title (common/get-property tree "og:title")
             html-title (first (:content (first (select/select (select/tag "title") tree))))
             title (or (when (seq og-title) og-title) (when (string? html-title) html-title) url)
             description (let [d (common/get-property tree "og:description")] (when (seq d) d))
             og-image (common/get-property tree "og:image")
             image (when (seq og-image)
                     (try (:body (http/get og-image
                                           {:as :byte-array
                                            :socket-timeout 10000
                                            :connection-timeout 5000}))
                          (catch Exception _e nil)))
             {:keys [date year]} (or (extract-date-from-url url) (extract-date-from-content tree))
             display-title (let [t (if description (str title " - " description) title)]
                             (subs t 0 (min 255 (count t))))]
         {:title display-title :date date :year year :image image})
       (catch Exception _e {:title url :date nil :year nil :image nil})))

(ns scrapers.substack
  (:require utils
            [clojure.string :as str]
            [hickory.select :as select]
            [hickory.core :as html]
            [clj-http.client :as http]))

(defn extract-content [hickory-tree]
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
  (get-post "https://woodfromeden.substack.com/p/the-anti-autism-manifesto" 
            extract-content))

(ns scrapers.substack
  (:require utils
            [clojure.string :as str]
            [hickory.select :as select]
            [hickory.core :as html]
            [clj-http.client :as http]
            scrapers.common))

(defn extract-content [hickory-tree]
  (:content (first (:content (first (select/select
     (select/and (select/tag "div")
                 (select/class "available-content")) hickory-tree))))))

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

(defn- extract-date-for-pods [tree]
   (let [base (select/select (select/descendant ;; difference is I don't filter for post-header
                              (select/tag "div")) tree)]
     (doall (->> base
                 (filter (fn [item] (string? (first (:content item)))))
                 (map (fn [item] (first (:content item))))
                 (filter (fn [item] (re-matches #"[A-Z][a-z]{2,4}\s\d\d,\s\d\d\d\d" item)))
                 first))))

(defn podcast-episode? [tree]
  (and
   (extract-date-for-pods tree)
   (not (extract-date tree))
   (try
     (scrapers.common/get-name tree "twitter:player")
     true
     (catch Exception _e
       false))))

(defn get-post [url extract-content]
  (let [tree (html/as-hickory (html/parse (:body (http/get url))))
        title (scrapers.common/get-property tree "og:title")
        subtitle (scrapers.common/get-property tree "og:description")
        image (scrapers.common/get-property tree "og:image")
        date  (or (extract-date tree) (extract-date-for-pods tree))
        [date year] (convert-date date)]
    {:title   (subs (str title " - " subtitle) 0 (min 255 
                                                      (count (str title " - " subtitle))))
     :date    date
     :year    year
     :image   (when image (:body (http/get image {:as :byte-array})))
     :content (-> tree 
                  extract-content  
                  scrapers.common/extract-text)
     :type (if (podcast-episode? tree)
             :podcast-episode
             :article)}))

(comment
  (def tree (html/as-hickory (html/parse (:body (http/get "https://astralflight.substack.com/p/mkultramerica-the-unabomber?utm_source=%2Finbox%2Fsaved&utm_medium=reader2")))))
  (extract-date-for-pods tree)
  (podcast-episode? tree)
  
  (:image (get-post "https://woodfromeden.substack.com/p/the-anti-autism-manifesto" 
                    extract-content))
  
  (def data (:body (http/get "https://substackcdn.com/image/fetch/w_1200,h_600,c_fill,f_jpg,q_auto:good,fl_progressive:steep,g_auto/https%3A%2F%2Fsubstack-post-media.s3.amazonaws.com%2Fpublic%2Fimages%2F824250b3-83d1-45f4-8c49-f7530aa9e9c6_1536x1024.jpeg" 
                             {:as :byte-array})))
  (require '[clojure.java.io :as io])
  (io/copy data (io/file "/Users/daniel/Desktop/test1.png"))
  )

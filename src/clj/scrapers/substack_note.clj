(ns scrapers.substack-note
  (:require utils
            [clojure.string :as str]
            [hickory.select :as select]
            [hickory.core :as html]
            [clj-http.client :as http]
            scrapers.common
            scrapers.substack.common))

(defn get-tree [url]
  (html/as-hickory
   (html/parse
    (:body (http/get url)))))

(defn get-date [tree]
   (let [[date-and-month year]
         (str/split (-> (select/select 
                         (select/attr "title"
                                      (fn [x]
                                        (when (and (string? x)
                                                   (some? x))
                                          (re-matches #"[A-Z][a-z]{2,4}\s\d{1,2},\s\d\d\d\d.*" x))))
                         tree)
                        first
                        :attrs
                        :title)
                    #",")
         year (str/trim year)
         [month day] (str/split date-and-month #"\s")
         _ (prn "?" day)
         month (scrapers.substack.common/convert-month month)
         day (format "%02d" (Integer/parseInt day))
         title (scrapers.common/get-property tree "og:description")]
     (prn month ".." day)
     {:date (str year "-" month "-" day)
      :year year
      :title title}))

(comment
  (def tree (html/as-hickory (html/parse (:body (http/get "https://substack.com/@theheavenlyheritage/note/c-80757692")))))
  (get-date tree))

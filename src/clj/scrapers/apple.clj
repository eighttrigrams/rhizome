(ns scrapers.apple
  (:require utils
            [hickory.core :as html]
            [clj-http.client :as http]
            scrapers.common))

(defn get-episode
  [url]
  (let [tree (html/as-hickory (html/parse (:body (http/get url))))
        title (scrapers.common/get-name tree "apple:title")]
    {:title title}))

(comment
  (:title (get-episode "")))
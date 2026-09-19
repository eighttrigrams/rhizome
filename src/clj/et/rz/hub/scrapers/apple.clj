(ns et.rz.hub.scrapers.apple
  (:require et.rz.utils
            [hickory.core :as html]
            [clj-http.client :as http]
            et.rz.hub.scrapers.common))

(defn get-episode
  [url]
  (let [tree (html/as-hickory (html/parse (:body (http/get url))))
        title (et.rz.hub.scrapers.common/get-name tree "apple:title")]
    {:title title}))

(comment
  (:title (get-episode "")))
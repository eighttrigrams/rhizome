(ns scrapers.youtube
  (:require utils
            [hickory.core :as html]
            [clj-http.client :as http]
            scrapers.common))

(defn get-video [url]
  (let [tree (html/as-hickory (html/parse (:body (http/get url))))
        image (scrapers.common/get-property tree "og:image")]
    {:image   (when image (:body (http/get image {:as :byte-array})))}))

(comment
  (:image (get-video "https://www.youtube.com/watch?v=kFw7e1Ao0B4")))

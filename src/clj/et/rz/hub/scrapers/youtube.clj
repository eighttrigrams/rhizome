(ns et.rz.hub.scrapers.youtube
  (:require et.rz.utils
            [hickory.core :as html]
            [clj-http.client :as http]
            et.rz.hub.scrapers.common))

(defn get-video
  [url]
  (let [tree (html/as-hickory (html/parse (:body (http/get url))))
        image (et.rz.hub.scrapers.common/get-property tree "og:image")]
    {:image (when image (:body (http/get image {:as :byte-array})))}))

(comment
  (:image (get-video "https://www.youtube.com/watch?v=asdfkasfs")))

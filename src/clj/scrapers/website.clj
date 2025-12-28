(ns scrapers.website
  (:require [hickory.select :as select]
            [hickory.core :as html]
            [clj-http.client :as http]
            [scrapers.common :as common]))

(defn get-metadata
  [url]
  (try (let [tree (html/as-hickory (html/parse (:body (http/get url
                                                                {:socket-timeout 5000
                                                                 :connection-timeout 5000}))))
             og-title (common/get-property tree "og:title")
             html-title (first (:content (first (select/select (select/tag "title") tree))))
             title (or (when (seq og-title) og-title) (when (string? html-title) html-title))
             og-image (common/get-property tree "og:image")
             image (when (seq og-image)
                     (try (:body (http/get og-image
                                           {:as :byte-array
                                            :socket-timeout 10000
                                            :connection-timeout 5000}))
                          (catch Exception _e nil)))]
         {:title title :image image})
       (catch Exception _e {:title nil :image nil})))

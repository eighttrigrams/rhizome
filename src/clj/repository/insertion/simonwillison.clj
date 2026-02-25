(ns repository.insertion.simonwillison
  (:require [et.vp.ds :as datastore]
            [cambium.core :as log]
            [repository.insertion.common :as common]
            [scrapers.simonwillison :as scraper]
            upload))

(defn match? [title] (re-matches #"https://simonwillison\.net/.*" title))

(defn ingest
  [db url context-ids-set _]
  (let [existing-item
          (datastore/get-item-by-path db "data->'resource-links'->>'simonwillison-article'" url)]
    (if (:id existing-item)
      (assoc (datastore/get-item db existing-item) :previously-existing-item? true)
      (let [{:keys [title date year image]} (scraper/get-post url)
            year (or year (str (.getValue (java.time.Year/now))))
            articles-id (common/get-item-or-throw-error db "Articles")
            year-id (common/get-item-or-throw-error db year)
            context-ids-set (conj context-ids-set articles-id year-id)
            item (common/insert-item db
                                     (or title url)
                                     ""
                                     context-ids-set
                                     {:simonwillison-article url})]
        (when date
          (try (datastore/insert-date db (:id item) date)
               (catch Exception e
                 (log/error (str "failed to set date for simonwillison article: "
                                 (.getMessage e))))))
        (when image
          (try (upload/upload-preview-file db {:tempfile image} (:id item) "false")
               (catch Exception e
                 (log/error (str "failed to create preview image for simonwillison article: "
                                 (.getMessage e))))))
        item))))

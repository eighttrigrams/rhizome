(ns repository.insertion.simonwillison
  (:require [et.vp.ds :as datastore]
            [cambium.core :as log]
            [repository.insertion.common :as common]
            [scrapers.simonwillison :as scraper]
            upload))

(def ^:private domain-url "https://simonwillison.net")

(defn match? [title] (re-matches #"https://simonwillison\.net/.*" title))

(defn- get-or-create-website
  [db websites-id]
  (let [website-id
          (:id (datastore/get-item-by-path db "data->'resource-links'->>'website-url'" domain-url))]
    (or
      website-id
      (:id
        (common/insert-item db "simonwillison.net" "" #{websites-id} {:website-url domain-url})))))

(defn ingest
  [db url context-ids-set _]
  (let [existing-item (datastore/get-item-by-path db "data->'resource-links'->>'webpage-url'" url)]
    (if (:id existing-item)
      (assoc (datastore/get-item db existing-item) :previously-existing-item? true)
      (let [{:keys [title date year image]} (scraper/get-post url)
            year (or year (str (.getValue (java.time.Year/now))))
            articles-id (common/get-item-or-throw-error db "Articles")
            year-id (common/get-item-or-throw-error db year)
            websites-id (common/get-item-or-throw-error db "Websites")
            website-id (get-or-create-website db websites-id)
            context-ids-set (conj context-ids-set articles-id year-id website-id)
            item (common/insert-item db (or title url) "" context-ids-set {:webpage-url url})]
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

(ns repository.insertion.website
  (:require [et.vp.ds :as datastore]
            [cambium.core :as log]
            [repository.insertion.common :as common]
            [scrapers.website :as scraper]
            upload)
  (:import [java.net URL]))

(defn match? [title] (re-matches #"https?://[^\s]+$" title))

(defn- parse-url
  [url-str]
  (let [url (URL. url-str)
        protocol (.getProtocol url)
        host (.getHost url)
        path (.getPath url)
        domain-url (str protocol "://" host)
        has-path? (and (seq path) (not= path "/"))]
    {:domain-url domain-url :full-url url-str :host host :has-path? has-path?}))

(defn- save-preview-image
  [db item-id image]
  (when image
    (try (upload/upload-preview-file db {:tempfile image} item-id "false")
         (catch Exception e
           (log/error (str "problem while trying to create preview image for webpage. message: "
                           (.getMessage e)))))))

(defn- create-website-with-metadata
  [db domain-url host context-ids-set]
  (let [{:keys [title image]} (scraper/get-metadata domain-url)
        item-title (or (when (seq title) (subs title 0 (min 255 (count title)))) host)
        item (common/insert-item db item-title "" context-ids-set {:website-url domain-url})]
    (save-preview-image db (:id item) image)
    item))

(defn- create-website-or-take-existing
  [db domain-url host websites-id]
  (let [website-id
          (:id (datastore/get-item-by-path db "data->'resource-links'->>'website-url'" domain-url))]
    (or website-id (:id (create-website-with-metadata db domain-url host #{websites-id})))))

(defn ingest
  [db url context-ids-set _]
  (let [{:keys [domain-url full-url host has-path?]} (parse-url url)
        websites-id (common/get-item-or-throw-error db "Websites")
        website-id (create-website-or-take-existing db domain-url host websites-id)
        existing-item
          (when has-path?
            (datastore/get-item-by-path db "data->'resource-links'->>'webpage-url'" full-url))]
    (cond (and has-path? (:id existing-item)) (assoc (datastore/get-item db existing-item)
                                                :previously-existing-item? true)
          has-path? (let [{:keys [title image]} (scraper/get-metadata full-url)
                          item-title (or (when (seq title) (subs title 0 (min 255 (count title))))
                                         full-url)
                          item (common/insert-item db
                                                   item-title
                                                   ""
                                                   (conj context-ids-set website-id)
                                                   {:webpage-url full-url})]
                      (save-preview-image db (:id item) image)
                      item)
          :else (let [existing-website (datastore/get-item-by-path
                                         db
                                         "data->'resource-links'->>'website-url'"
                                         domain-url)]
                  (if (:id existing-website)
                    (assoc (datastore/get-item db existing-website) :previously-existing-item? true)
                    (create-website-with-metadata db
                                                  domain-url
                                                  host
                                                  (conj context-ids-set websites-id)))))))

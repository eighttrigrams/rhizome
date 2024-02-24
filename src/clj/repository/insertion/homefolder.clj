(ns repository.insertion.homefolder
  (:require [cambium.core :as log]
            [clojure.string :as str]
            [clojure.set :as set]
            datastore
            [datastore.get-item :as get-item]
            [utils :refer [condx]]
            [repository.insertion.common :as common]
            [repository.homefolder :as home]))

;; TODO make condx work to match multiple cases, like case
(defn- classify [title]
  (condx #(str/ends-with? (str/lower-case title) %) 
         "mp3" ["MP3s" "Audio"] 
         "mp4" ["MP4s" "Video"]
         "pdf" ["PDFs"]
         "jpg" ["JPEGs" "Image"]
         "jpeg" ["JPEGs" "Image"]
         "png" ["PNGs" "Image"]))

(defn- get-additional-context-ids [db additional-context-titles]
  (let [additional-context-ids 
        (mapv #(common/get-item-or-throw-error db %) additional-context-titles)]
    additional-context-ids))

(defn strip-suffix [title]
  (subs title 0 (str/last-index-of title ".")))

(defn- validate-not-exists [db file-name]
  (home/validate-not-exists file-name)
  (when (:id (get-item/get-item-by-path db "data->'resource-links'->>'file'" file-name))
    (throw (Exception. "file already exists!")))
  (when (:id (get-item/get-item-by-path db "data->'resource-links'->>'image'" file-name))
    (throw (Exception. "image already exists!"))))

(defn save-file [db file-name context-ids-set]
  (validate-not-exists db file-name) 
  (let [classification (classify file-name)
        files-context-id (common/get-item-or-throw-error db "Files") 
        additional-context-ids (get-additional-context-ids db classification)
        context-ids-set (conj (set/union context-ids-set 
                                         (into #{} additional-context-ids))
                              files-context-id)
        resource-links (merge {:file file-name}
                              (when (some #{"Image"} classification)
                                {:image file-name}))]
    (common/insert-item db 
                        (strip-suffix file-name)
                        "" 
                        context-ids-set 
                        resource-links)))

(defn batch-insertion [db]
  (log/info "Startin batch insertion ...")
  (let [import-id (common/get-item-or-throw-error db "Imports")]
    (log/info (str "1 " (home/list-files)))
    (log/info (str "2 " (doall (home/list-files))))
    (doall 
     (for [file-name (home/list-files)]
       (when (home/supported-file-type? file-name)
         (log/info (str "Importing " file-name " ... "))
         (try
           (save-file db file-name #{import-id})
           (home/move-file file-name)
           (catch Exception e 
             (log/error (.getMessage e)))))))))

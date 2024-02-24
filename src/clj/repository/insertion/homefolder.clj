(ns repository.insertion.homefolder
  (:require [cambium.core :as log]
            [clojure.string :as str]
            [clojure.set :as set]
            datastore
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

(defn save-file [db file-name context-ids-set]
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
  (let [import-id (common/get-item-or-throw-error db "Imports")]
    (for [file-name (home/list-files)]
      (do
        (log/info (str "Importing " file-name " ... "))
        (try
          (home/validate-not-exists file-name)
          (save-file db file-name #{import-id})
          (home/move-file file-name)
          (catch Exception e 
            (log/error (.getMessage e))))))))

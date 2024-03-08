(ns repository.insertion.file
  (:require [clojure.string :as str]
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
         "wav" ["WAVs" "Audio"] 
         "mp4" ["MP4s" "Video"]
         "flv" ["FLVs" "Video"]
         "mov" ["MOVs" "Video"]
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

(defn match? [title]
  (home/supported-file-type? title))

(defn ingest [db file-name context-ids-set _]
  (validate-not-exists db file-name) 
  (when (re-find #"," file-name)
    (throw (Exception. (str "file name shouldn't contain commas: " file-name))))
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

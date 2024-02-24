(ns repository.insertion.homefolder
  (:require [clojure.string :as str]
            [clojure.set :as set]
            datastore
            [utils :refer [condx]]
            [repository.insertion.common :as common]))

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

(defn save-file [db title context-ids-set]
  (let [classification (classify title)
        files-context-id (common/get-item-or-throw-error db "Files") 
        additional-context-ids (get-additional-context-ids db classification)
        context-ids-set (conj (set/union context-ids-set 
                                         (into #{} additional-context-ids))
                              files-context-id)
        title (strip-suffix title)
        resource-links (merge {:file title}
                               (when (some #{"Image"} classification)
                                 {:image title}))] 
    (common/insert-item db title "" context-ids-set resource-links)))

(ns repository.insertion.homefolder
  (:require [clojure.string :as str]
            datastore
            [utils :refer [condx]]
            [repository.insertion.common :as common]))

(defn- classify [title]
  (condx #(str/ends-with? (str/lower-case title) %) 
         "mp3" ["MP3s" "Audio"] 
         "mp4" ["MP4s" "Video"]
         "pdf" ["PDFs"]
         "jpeg" ["JPEGs" "Image"]
         "png" ["PNGs" "Image"]))

(defn- get-additional-context-ids [db additional-context-titles]
  (let [additional-context-ids 
        (mapv #(common/get-item-or-throw-error db %) additional-context-titles)]
    additional-context-ids))

(defn strip-suffix [title]
  (subs title 0 (str/last-index-of title ".")))

(defn save-file [db title selected-context-id]
  (let [classification (classify title)
        files-context-id (common/get-item-or-throw-error db "Files") 
        additional-context-ids (get-additional-context-ids db classification)] 
    (common/insert-item db 
                        (strip-suffix title) 
                        selected-context-id 
                        (conj additional-context-ids files-context-id)
                        (merge {:file title}
                               (when (some #{"Image"} classification)
                                 {:image title})))))

(ns repository.insertion.homefolder
  (:require [clojure.string :as str]
            datastore
            [utils :refer [condx]]
            [repository.insertion.common :as common]))

(defn- get-additional-context-ids' [title]
  (condx #(str/ends-with? (str/lower-case title) %) 
         "mp3" ["MP3s" "Audio"] 
         "mp4" ["MP4s" "Video"]
         "pdf" ["PDFs"]))

(defn- get-additional-context-ids [db title]
  (let [additional-context-titles (get-additional-context-ids' title)
        additional-context-ids 
          (mapv #(common/get-item-or-throw-error db %) additional-context-titles)]
    additional-context-ids))

(defn save-file [db title selected-context-id]
  (let [files-context-id (common/get-item-or-throw-error db "Files")
        additional-context-ids (get-additional-context-ids db title)]
    (common/insert-item db 
                        title 
                        selected-context-id 
                        (conj additional-context-ids files-context-id)
                        {:file title})))

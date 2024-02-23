(ns repository.insertion.homefolder
  (:require [clojure.string :as str]
            datastore
            [datastore.get-item :as get-item]))

;; TODO move to some util
(defn- condx [p & pairs]
  (first (keep (fn [[v f]]
                 (when (p v) f))
               (partition 2 pairs))))

(defn- get-additional-context-ids' [title]
  (condx #(str/ends-with? (str/lower-case title) %) 
         "mp3" ["MP3s" "Audio"] 
         "mp4" ["MP4s" "Video"]
         "pdf" ["PDFs"]))

(defn- get-item-or-throw-error [db title]
  (let [id (:id (get-item/get-item-by-title db {:title title}))
        _ (when-not id (throw (Exception. (str "no id for " title))))]
    id))

(defn- get-additional-context-ids [db title]
  (let [additional-context-titles (get-additional-context-ids' title)
        additional-context-ids 
          (mapv #(get-item-or-throw-error db %) additional-context-titles)]
    additional-context-ids))

;; TODO extract this common pattern of new-issue update-issue
(defn- create-item 
  [db 
   title
   selected-context-id 
   additional-contexts-ids]
  (let [item (datastore/new-issue db
                                   title
                                   ""
                                   selected-context-id
                                   additional-contexts-ids)
        item (datastore/update-issue
              db {:issue (update item 
                                 :data(fn [data] 
                                             (assoc data :resource-links {:file title})))
                         :related-issues-ids '()})]
    item))

(defn save-file [db title selected-context-id]
  (let [files-context-id (get-item-or-throw-error db "Files")
        additional-context-ids (get-additional-context-ids db title)]
    (create-item db 
                 title 
                 selected-context-id 
                 (conj additional-context-ids files-context-id))))

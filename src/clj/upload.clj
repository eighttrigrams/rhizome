(ns upload
  (:require [clojure.java.io :as io]
            [cambium.core :as log]
            datastore))

(defn upload-preview-file [db uploaded-file id]
  (let [_ 1]
    (try
      (log/info (str "Uploading preview file: " id))
      (let [item (datastore/get-issue db {:id id})
            data (or (:data item) {})
            data (assoc-in data [:preview-image] (str id ".png"))]
        (datastore/update-issue-simple db (assoc item :data data))) 
      (io/copy (:tempfile uploaded-file) (io/file (str "/Users/daniel/Pictures/Tracked/Preview/" 
                                                       id 
                                                       ".png")))
      (catch Exception e
        (log/error (str "Problem with image upload. Message: " (.getMessage e)))))))

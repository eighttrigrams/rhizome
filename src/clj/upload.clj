(ns upload
  (:require [clojure.java.io :as io]
            [cambium.core :as log]
            [et.vp.ds :as datastore]
            [clojure.java.shell :refer [sh]]))

(def homefolder (-> (read-string (slurp "./config.edn")) :folders :homefolder))

(defn upload-preview-file [db uploaded-file id alternative-behaviour?]
  (let [_ 1]
    (try
      (log/info (str "Uploading preview file: " id))
      (let [item (datastore/get-item db {:id id})
            downscale-image? (= "false" alternative-behaviour?)
            data (or (:data item) {})
            data (if downscale-image?  
                   (-> data 
                       (assoc :preview-image-lowres (str id ".png"))
                       ;; introduced because of the comment 2 below
                       (assoc :lowres? true)
                       ;; this doesn't work properly as update-item will merge the data with the old data
                       (dissoc :preview-image))
                   (-> data 
                       (assoc :preview-image (str id ".png"))
                       ;; introduced because: see comment below
                       (assoc :lowres? false)
                       ;; this doesn't work properly as update-item will merge the data with the old data
                       (dissoc :preview-image-lowres)))
            path (str homefolder "Pictures/Tracked/Preview/" 
                      id 
                      ".png")
            lowres-path (str homefolder "Pictures/Tracked/Preview/Lowres/" 
                             id 
                             ".png")]
        (datastore/update-item db (assoc item :data data)) 
        (io/copy (:tempfile uploaded-file) (io/file path))
        (when downscale-image?
          (log/info "Will downscale image now")
          (sh "convert" path "-resize" "x200" lowres-path)
          (io/delete-file (io/file path))))
      (catch Exception e
        (log/error (str "Problem with image upload. Message: " (.getMessage e)))))))

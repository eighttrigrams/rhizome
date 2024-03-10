(ns upload
  (:require [clojure.java.io :as io]
            [cambium.core :as log]
            datastore
            [clojure.java.shell :refer [sh]]))

(defn upload-preview-file [db uploaded-file id low-res?]
  (let [_ 1]
    (try
      (log/info (str "Uploading preview file: " id))
      (let [item (datastore/get-issue db {:id id})
            youtube-video? (-> item :data :resource-links :youtube-video)
            downscale-image? (or (= "true" low-res?)
                                 youtube-video?)
            data (or (:data item) {})
            data (if downscale-image?  
                   (-> data 
                       (assoc :preview-image-lowres (str id ".png"))
                       (dissoc :preview-image))
                   (-> data 
                       (assoc :preview-image (str id ".png"))
                       (dissoc :preview-image-lowres)))
            path (str "/Users/daniel/Pictures/Tracked/Preview/" 
                      id 
                      ".png")
            lowres-path (str "/Users/daniel/Pictures/Tracked/Preview/Lowres/" 
                             id 
                             ".png")]
        (datastore/update-issue-simple db (assoc item :data data)) 
        (io/copy (:tempfile uploaded-file) (io/file path))
        (when downscale-image?
          (log/info "Will downscale image now")
          (sh "convert" path "-resize" "x200" lowres-path)
          (io/delete-file (io/file path))))
      (catch Exception e
        (log/error (str "Problem with image upload. Message: " (.getMessage e)))))))

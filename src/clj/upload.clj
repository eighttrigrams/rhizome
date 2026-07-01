(ns upload
  (:require [clojure.java.io :as io]
            [cambium.core :as log]
            [config :as config]
            [et.vp.ds :as datastore]
            [clojure.java.shell :refer [sh]]))

;; Drag-and-drop preview uploads land in the configured preview-images folder
;; (served at /imgs/Preview/*); the downscaled variant goes in its Lowres/
;; subfolder (served at /imgs/Preview/Lowres/*).
(def ^:private preview-images (get-in config/config [:folders :preview-images]))

(defn ensure-convert!
  []
  (when-not (try (zero? (:exit (sh "convert" "-version")))
                 (catch Exception _ false))
    (throw (Exception. (str "ImageMagick `convert` not found on PATH. "
                            "Preview downscaling needs it; refusing to start. "
                            "Install imagemagick (it ships in the docker image).")))))

(defn upload-preview-file
  [db uploaded-file id alternative-behaviour?]
  (let [_ 1]
    (try (log/info (str "Uploading preview file: " id))
         (let [item (datastore/get-item db {:id id})
               downscale-image? (= "false" alternative-behaviour?)
               data (or (:data item) {})
               data (if downscale-image?
                      (-> data
                          (assoc :preview-image-lowres (str id ".png"))
                          ;; introduced because of the comment 2 below
                          (assoc :lowres? true)
                          ;; this doesn't work properly as update-item will merge the data with
                          ;; the old data
                          (dissoc :preview-image))
                      (-> data
                          (assoc :preview-image (str id ".png"))
                          ;; introduced because: see comment below
                          (assoc :lowres? false)
                          ;; this doesn't work properly as update-item will merge the data with
                          ;; the old data
                          (dissoc :preview-image-lowres)))
               file (io/file preview-images (str id ".png"))
               lowres-file (io/file preview-images "Lowres" (str id ".png"))]
           (datastore/update-item db (assoc item :data data))
           (io/copy (:tempfile uploaded-file) file)
           (when downscale-image?
             (log/info "Will downscale image now")
             (sh "convert" (str file) "-resize" "x200" (str lowres-file))
             (io/delete-file file)))
         (catch Exception e
           (log/error (str "Problem with image upload. Message: " (.getMessage e)))))))

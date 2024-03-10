(ns repository.deletion
  (:require [clojure.java.io :as io]
            [cambium.core :as log]
            datastore
            [datastore.get-item :as get-item]))

(defn- get-files-count [db file]
  (if file
    (count (get-item/get-items-by-path db 
                                       "data->'resource-links'->>'file'"
                                       file))
    0))

(defn- file-path [folder file]
  (str "/Users/daniel/" folder "/Tracked/" file))

(defn- found-files [file]
  (if-not file
    []
    (filter 
     (fn [folder] 
       (.exists (io/file (file-path folder file))))
     ["Pictures" "Music" "Documents"])))

(defn- delete-file [found-files file]
  (when file
    (if (= (count found-files) 0)
      (log/warn (str "No file found to delete."))
      (let [file-path (file-path (first found-files) file)]
        (log/info (str "Will delete file " file-path))
        (io/delete-file (io/file file-path))))))

(defn- delete-preview-images [id]
  (let [highres-path (str "/Users/daniel/Pictures/Tracked/Preview/" id ".png")
        lowres-path (str "/Users/daniel/Pictures/Tracked/Preview/Lowres/" id ".png")]
    (when (.exists (io/file highres-path))
      (log/info (str "Will remove " highres-path))
      (.delete (io/file highres-path)))
    (when (.exists (io/file lowres-path))
      (log/info (str "Will remove " lowres-path))
      (.delete (io/file lowres-path)))))

(defn delete-item
  [db {:keys [id]
       {{:keys [file]} :resource-links} :data}]
  (log/info (str "Prepare deleting item with id '" id "'" 
                 (when file (str " and file name '" file "'"))))
  (let [contained-items-count (datastore/get-contained-items-count db id)
        files-count (get-files-count db file)
        found-files (found-files file)]
    (cond (> contained-items-count 0)
          (log/warn (str "Doing nothing. Item to be deleted still contains items."))
          (> files-count 1)
          (log/warn (str "Doing nothing. Files count for file is greater than one. (" files-count ")"))
          (> (count found-files) 1)
          (log/warn (str "Doing nothing. Too many files found. (" (count found-files) ") "))
          :else
          (do (delete-file found-files file)
              (datastore/delete-item db {:id id})
              (delete-preview-images id)))))

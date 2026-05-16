(ns repository.deletion
  (:require [clojure.java.io :as io]
            [cambium.core :as log]
            [config :as config]
            [et.vp.ds :as datastore]))

(def homefolder (get-in config/config [:folders :homefolder]))

(defn- get-files-count
  [db file]
  (if file (count (datastore/get-items-by-path db "data->'resource-links'->>'file'" file)) 0))

(defn- file-path [folder file] (str homefolder folder "/Tracked/" file))

(defn- found-files
  [file]
  (if-not file
    []
    (filter (fn [folder] (.exists (io/file (file-path folder file))))
      ["Pictures" "Music" "Documents"])))

(defn- delete-file
  [found-files file]
  (when file
    (if (= (count found-files) 0)
      (log/warn (str "No file found to delete."))
      (let [file-path (file-path (first found-files) file)]
        (log/info (str "Will delete file " file-path))
        (io/delete-file (io/file file-path))))))

(defn- delete-preview-images
  [id]
  (let [highres-path (str homefolder "Pictures/Tracked/Preview/" id ".png")
        lowres-path (str homefolder "Pictures/Tracked/Preview/Lowres/" id ".png")]
    (when (.exists (io/file highres-path))
      (log/info (str "Will remove " highres-path))
      (.delete (io/file highres-path)))
    (when (.exists (io/file lowres-path))
      (log/info (str "Will remove " lowres-path))
      (.delete (io/file lowres-path)))))

(defn delete-item
  "Delete an item, with safety checks. Returns a status map:
   {:status :deleted}                   — done (or, in dry-run, would be done)
   {:status :skipped :reason :has-children}
   {:status :skipped :reason :multiple-file-references}
   {:status :skipped :reason :multiple-files-found}
   When dry-run? is true, the checks still run but no side effects are
   performed — used by the deletion-preview REST endpoint so its skipped
   list matches what an actual delete would do."
  ([db item] (delete-item db item false))
  ([db {:keys [id] {{:keys [file]} :resource-links} :data} dry-run?]
   (log/info (str (if dry-run? "[dry-run] " "")
                  "Prepare deleting item with id '" id
                  "'" (when file (str " and file name '" file "'"))))
   (let [contained-items-count (datastore/get-contained-items-count db id)
         files-count (get-files-count db file)
         found-files (found-files file)]
     (cond (> contained-items-count 0)
             (do (log/warn (str "Doing nothing. Item to be deleted still contains items."))
                 {:status :skipped :reason :has-children})
           (> files-count 1)
             (do (log/warn (str "Doing nothing. Files count for file is greater than one. ("
                                files-count ")"))
                 {:status :skipped :reason :multiple-file-references})
           (> (count found-files) 1)
             (do (log/warn (str "Doing nothing. Too many files found. ("
                                (count found-files) ") "))
                 {:status :skipped :reason :multiple-files-found})
           :else (do (when-not dry-run?
                       (delete-file found-files file)
                       (datastore/delete-item db {:id id})
                       (delete-preview-images id))
                     {:status :deleted})))))

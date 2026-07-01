(ns repository.homefolder
  (:require [cambium.core :as log]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [config :as config]))

(defn- folder
  "Absolute path of the configured media folder `k` (e.g. :imports, :images)."
  [k]
  (get-in config/config [:folders k]))

(defn folder-exists?
  "True when the configured folder `k` exists on disk. Folders are validated as
   configured at config load and warned about at startup, but may still vanish
   at runtime (e.g. an unmounted drive), so import/deletion re-check here."
  [k]
  (.isDirectory (io/file (folder k))))

(defn- get-suffix
  [file-name]
  (let [idx (str/last-index-of file-name ".")] (str/lower-case (subs file-name (inc idx)))))

;; when adding files, also see file.clj (this here is 1 of 3 places)
(defn supported-file-type?
  [file-name]
  (or (str/ends-with? (str/lower-case file-name) ".mp4")
      (str/ends-with? (str/lower-case file-name) ".flv")
      (str/ends-with? (str/lower-case file-name) ".mov")
      (str/ends-with? (str/lower-case file-name) ".mp3")
      (str/ends-with? (str/lower-case file-name) ".ogg")
      (str/ends-with? (str/lower-case file-name) ".m4a")
      (str/ends-with? (str/lower-case file-name) ".wav")
      (str/ends-with? (str/lower-case file-name) ".pdf")
      (str/ends-with? (str/lower-case file-name) ".tiff")
      (str/ends-with? (str/lower-case file-name) ".jpeg")
      (str/ends-with? (str/lower-case file-name) ".jpg")
      (str/ends-with? (str/lower-case file-name) ".png")
      (str/ends-with? (str/lower-case file-name) ".webp")))

;; suffix → the configured folder a file of that type is filed under.
;; when adding files, also see file.clj (this here is 1 of 3 places)
(defn folder-key-for
  [file-name]
  (case (get-suffix file-name)
    ("mp3" "wav" "ogg" "m4a")   :audio
    ("mp4" "flv" "mov")         :video
    ("pdf" "tiff")              :docs
    ("jpeg" "jpg" "png" "webp") :images
    nil))

(defn importable?
  "True when the file's type maps to a configured destination folder that
   exists. Files whose destination folder is missing are left untouched in
   :imports on batch import (and a warn is logged by the caller)."
  [file-name]
  (boolean (when-let [k (folder-key-for file-name)]
             (folder-exists? k))))

(defn validate-not-exists
  [file-name]
  (doseq [k [:audio :images :docs :video]]
    (when (.exists (io/file (folder k) file-name))
      (throw (Exception. (str "File already exists: " file-name))))))

(defn get-target
  [file-name]
  (when-let [k (folder-key-for file-name)]
    (str (io/file (folder k) file-name))))

(defn ren
  [file-name target]
  (log/info (str "Will rename " file-name " to " target))
  (.renameTo (io/file (folder :imports) file-name)
             (io/file (folder :imports) target)))

(defn move-file
  [file-name]
  (let [target (get-target file-name)]
    (log/info (str "Will move " file-name " to " target))
    (.renameTo (io/file (folder :imports) file-name) (io/file target))))

(defn list-files
  []
  (if-not (folder-exists? :imports)
    (do (log/warn (str "Imports folder does not exist: " (folder :imports)
                       " -- cannot import anything."))
        [])
    (->> (vec (file-seq (io/file (folder :imports))))
         (filter #(not (.isDirectory %)))
         (map #(.getName %)))))

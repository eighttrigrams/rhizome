(ns et.rz.hub.repository.homefolder
  (:require [cambium.core :as log]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [et.rz.config :as config]
            [et.rz.names :as names]))

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
    ;; In either Unicode form: on Linux an NFD twin is another name (et.rz.names).
    (when (some #(.exists (io/file (folder k) %)) (names/forms file-name))
      (throw (Exception. (str "File already exists: " file-name))))))

(defn get-target
  [file-name]
  (when-let [k (folder-key-for file-name)]
    ;; The file as it is on disk, NFC or NFD; for one not there yet, NFC.
    ;; Inside the folder either way, or nil: the name is free text
    ;; (et.rz.names/path-in).
    (some-> (or (names/resolve-in (folder k) file-name)
                (names/path-in (folder k) (names/nfc file-name)))
            str)))

(defn- taken?
  "Whether `target` in `dir` is already another file than `from`, in either
   Unicode form. Not the same file: on APFS the NFC form of an NFD name *is*
   that file, and renaming it to itself is fine."
  [^java.io.File from dir target]
  (some (fn [form]
          (let [f (io/file dir form)]
            (and (.exists f)
                 (not (java.nio.file.Files/isSameFile (.toPath from) (.toPath f))))))
        (names/forms target)))

(defn ren
  "Rename in imports. False, without renaming, where the new name is taken:
   File.renameTo overwrites on Linux."
  [file-name target]
  (log/info (str "Will rename " file-name " to " target))
  (let [from (io/file (folder :imports) file-name)]
    (if (taken? from (folder :imports) target)
      (do (log/error (str "Not renaming " file-name ": " target " already exists in imports"))
          false)
      (.renameTo from (io/file (folder :imports) target)))))

(defn move-file
  "Move out of imports into the file's folder. Throws rather than overwrite
   (File.renameTo would, on Linux) or leave a failed move unsaid."
  [file-name]
  (let [target (get-target file-name)]
    (log/info (str "Will move " file-name " to " target))
    (cond
      (nil? target)
      (throw (Exception. (str "No place to move " file-name " to")))

      ;; get-target answers the file on disk where there is one, in either form
      (.exists (io/file target))
      (throw (Exception. (str "Not moving " file-name ": " target " already exists")))

      (not (.renameTo (io/file (folder :imports) file-name) (io/file target)))
      (throw (Exception. (str "Could not move " file-name " to " target))))))

(defn list-files
  []
  (if-not (folder-exists? :imports)
    (do (log/warn (str "Imports folder does not exist: " (folder :imports)
                       " -- cannot import anything."))
        [])
    ;; The top level only, regular files only: file-seq walked subfolders too
    ;; and gave their files by bare name, which then do not exist in imports
    ;; -- Syncthing's .stversions/, for one.
    (->> (.listFiles (io/file (folder :imports)))
         (filter #(.isFile ^java.io.File %))
         (map #(.getName ^java.io.File %)))))

(ns repository.homefolder
  (:require [cambium.core :as log]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn- get-suffix [file-name]
  (let [idx (str/last-index-of file-name ".")]
    (str/lower-case (subs file-name (inc idx)))))

;; when adding files, also see file.clj (this here is 1 of 3 places)
(defn supported-file-type? [file-name]
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
      (str/ends-with? (str/lower-case file-name) ".webp")
      ))

(defn validate-not-exists [file-name]
  (when (.exists (io/file (str "/Users/daniel/Music/Tracked/" file-name)))
    (throw (Exception. (str "File already exists: " file-name))))
  (when (.exists (io/file (str "/Users/daniel/Pictures/Tracked/" file-name)))
    (throw (Exception. (str "File already exists: " file-name))))
  (when (.exists (io/file (str "/Users/daniel/Documents/Tracked/" file-name)))
    (throw (Exception. (str "File already exists: " file-name))))
  (when (.exists (io/file (str "/Users/daniel/Movies/Tracked/" file-name)))
    (throw (Exception. (str "File already exists: " file-name)))))

;; when adding files, also see file.clj (this here is 1 of 3 places)
(defn get-target [file-name]
  (str "/Users/daniel/"
       (case (get-suffix file-name)
         ("mp3" "wav" "ogg" "m4a") "Music"
         ("mp4" "flv" "mov") "Movies"
         ("pdf" "tiff") "Documents"
         ("jpeg" "jpg" "png" "webp") "Pictures"
         nil)
       "/Tracked/" file-name))

(defn move-file [file-name]
  (let [target (get-target file-name)]
    (log/info (str "Will move " file-name " to " (str/replace target file-name "")))
    (.renameTo (io/file (str "/Users/daniel/Downloads/Tracked/" file-name))
               (io/file target))))

(defn list-files []
  (->> (vec (file-seq (io/file "/Users/daniel/Downloads/Tracked/")))
       (filter #(not (.isDirectory %)))
       (map #(.getName %))))

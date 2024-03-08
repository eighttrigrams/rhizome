(ns repository.homefolder
  (:require [cambium.core :as log]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn- get-suffix [file-name]
  (let [idx (str/last-index-of file-name ".")]
    (str/lower-case (subs file-name (inc idx)))))

(defn supported-file-type? [file-name]
  (or (str/ends-with? (str/lower-case file-name) ".mp4")
      (str/ends-with? (str/lower-case file-name) ".flv")
      (str/ends-with? (str/lower-case file-name) ".mp3")
      (str/ends-with? (str/lower-case file-name) ".pdf")
      (str/ends-with? (str/lower-case file-name) ".jpeg")
      (str/ends-with? (str/lower-case file-name) ".jpg")
      (str/ends-with? (str/lower-case file-name) ".png")))

(defn validate-not-exists [file-name]
  (when (.exists (io/file (str "/Users/daniel/Music/Tracked/" file-name)))
    (throw (Exception. (str "File already exists: " file-name))))
  (when (.exists (io/file (str "/Users/daniel/Pictures/Tracked/" file-name)))
    (throw (Exception. (str "File already exists: " file-name))))
  (when (.exists (io/file (str "/Users/daniel/Documents/Tracked/" file-name)))
    (throw (Exception. (str "File already exists: " file-name))))
  (when (.exists (io/file (str "/Users/daniel/Movies/Tracked/" file-name)))
    (throw (Exception. (str "File already exists: " file-name)))))

(defn get-target [file-name]
  (str "/Users/daniel/"
       (case (get-suffix file-name)
         "mp3" "Music"
         ("mp4" "flv") "Movies"
         ("pdf") "Documents"
         ("jpeg" "jpg" "png") "Pictures"
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

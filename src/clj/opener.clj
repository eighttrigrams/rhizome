(ns opener
  (:require [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- resolve-it [file-id]
  (let [suffix-id (str/last-index-of file-id ".")
        suffix (subs file-id (inc suffix-id))]
    (case (str/lower-case suffix)
      ("mp3" "mp4") "Music" 
      "pdf" "Documents"
      ("png" "jpeg" "jpg") "Pictures"
      nil)))

(defn open [file-id]
  (when-let [intermediate (resolve-it file-id)]
    (let [path (str "/Users/daniel/" intermediate "/Tracked/" file-id)]
      (when (.exists (io/file path))
        (sh/sh "open" path)))))

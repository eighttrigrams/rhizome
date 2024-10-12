#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]])

(def source-format-suffix "HEIC")
(def target-format-suffix "jpeg")

(def directory "/Users/daniel/Downloads/formats/")
;; will convert from subfolders (!) of source-folder
;; doesn't convert from the top level of that folder
;; (this is a quirk)

(defn convert-source-format-to-jpeg [file]
  (let [idx (.lastIndexOf file ".")
        jpeg-file (str (subs file 0 idx) "." target-format-suffix)]
    (shell "convert" file jpeg-file)
    (shell "rm" file)))

(defn find-and-convert [dir]
  (doseq [file (fs/glob (fs/file dir) (str "**/*." source-format-suffix))]
    (convert-source-format-to-jpeg (str file))))

(println (str "Starting conversion of " source-format-suffix " files to " target-format-suffix "..."))
(find-and-convert directory)
(println "Conversion completed.")

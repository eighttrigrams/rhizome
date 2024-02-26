(ns opener
  (:require [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [repository.homefolder :as home]))

(defn open [file-id]
  (let [path (home/get-target file-id)]
    (when (.exists (io/file path))
      (sh/sh "open" path))))

(ns repository.homefolder
  (:require [clojure.string :as str]))

(defn supported-file-type? [file-name]
  (or (str/ends-with? (str/lower-case file-name) ".mp4")
              (str/ends-with? (str/lower-case file-name) ".mp3")
              (str/ends-with? (str/lower-case file-name) ".pdf")
              (str/ends-with? (str/lower-case file-name) ".jpeg")
              (str/ends-with? (str/lower-case file-name) ".jpg")
              (str/ends-with? (str/lower-case file-name) ".png")))

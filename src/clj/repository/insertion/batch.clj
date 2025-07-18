(ns repository.insertion.batch
  (:require [cambium.core :as log]
            [clojure.string :as str]
            [repository.insertion.file :as file]
            [repository.homefolder :as home]
            [repository.insertion.common :as common]))

(defn match? [title]
  (str/starts-with? title "IMPORT"))

(defn ingest [db title _ _]
  (log/info "Starting batch insertion ...")
  (let [extra (str/trim (or (get (str/split title #"IMPORT") 1) ""))
        import-id (common/get-item-or-throw-error db "Imports")]
    (doall 
     (for [file-name (home/list-files)]
       (let [orig-file-name file-name
             file-name (if-not (empty? extra) 
                         (str extra " " orig-file-name)
                         orig-file-name)]
         (when (home/supported-file-type? file-name)
           (when-not (empty? extra) (home/ren orig-file-name file-name))
           (log/info (str "Importing " file-name " ... "))
           (try
             (file/ingest db file-name #{import-id} nil)
             (home/move-file file-name)
             (catch Exception e 
               (log/error (.getMessage e))))))))))

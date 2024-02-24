(ns repository.insertion.batch
  (:require [cambium.core :as log]
            [repository.insertion.file :as file]
            [repository.homefolder :as home]
            [repository.insertion.common :as common]))

(defn match? [title]
  (= "IMPORT" title))

(defn ingest [db _ _ _]
  (log/info "Startin batch insertion ...")
  (let [import-id (common/get-item-or-throw-error db "Imports")]
    (doall 
     (for [file-name (home/list-files)]
       (when (home/supported-file-type? file-name)
         (log/info (str "Importing " file-name " ... "))
         (try
           (file/ingest db file-name #{import-id} nil)
           (home/move-file file-name)
           (catch Exception e 
             (log/error (.getMessage e)))))))))

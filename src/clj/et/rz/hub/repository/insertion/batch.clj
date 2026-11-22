(ns et.rz.hub.repository.insertion.batch
  (:require [cambium.core :as log]
            [clojure.string :as str]
            [et.rz.hub.repository.insertion.file :as file]
            [et.rz.hub.repository.homefolder :as home]
            [et.rz.names :as names]
            [et.rz.config :as config]
            [clojure.java.io :as io]
            [et.rz.hub.repository.insertion.arrival :as arrival]
            [et.rz.hub.repository.insertion.common :as common]))

(defn match? [title] (str/starts-with? title "IMPORT"))

(defn ingest
  [db title _ _]
  (log/info "Starting batch insertion ...")
  (let [extra (str/trim (or (get (str/split title #"IMPORT") 1) ""))
        import-id (common/get-item-or-throw-error db "Imports")
        dir (get-in config/config [:folders :imports])
        ;; Files still on their way -- a temporary name, or one still being
        ;; written -- are left quietly for the next import (arrival).
        present (remove arrival/arriving? (home/list-files))
        unsettled (arrival/unsettled dir present)]
    (doseq [n unsettled] (log/debug (str "Leaving " n " for the next import: still being written")))
    (doall (for [file-name (remove unsettled present)]
             (let [orig-file-name file-name
                   wanted (if-not (empty? extra) (str extra " " orig-file-name) orig-file-name)
                   ;; Repaired, on disk (the ren below) and so in the db: NFC,
                   ;; no / or control characters, no spaces at the ends, at
                   ;; most 255 bytes (et.rz.names/repair).
                   file-name (names/repair wanted)]
               (when (not= wanted file-name)
                 (log/info (str "Repairing the name " (pr-str wanted) " -> " (pr-str file-name))))
               (when (home/supported-file-type? file-name)
                 (if-not (home/importable? file-name)
                   (log/warn (str "Skipping " orig-file-name
                                  " -- its destination folder does not exist; left in imports."))
                   (if-let [problem (arrival/content-problem (io/file dir orig-file-name) file-name)]
                     (log/warn (str "Skipping " orig-file-name " -- " problem "; left in imports."))
                     (if (and (not= orig-file-name file-name)
                              (not (home/ren orig-file-name file-name)))
                       ;; The rename failed (the name is taken, say): the file
                       ;; stays in imports under its old name, and is not filed
                       ;; under a name it does not have.
                       (log/error (str "Skipping " orig-file-name
                                       " -- could not rename it to " file-name "; left in imports."))
                       (do
                         (log/info (str "Importing " file-name " ... "))
                         (try (file/ingest db file-name #{import-id} nil)
                              (home/move-file file-name)
                              (catch Exception e (log/error (.getMessage e))))))))))))))

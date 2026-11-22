(ns et.rz.hub.repository.insertion.batch
  (:require [cambium.core :as log]
            [clojure.string :as str]
            [et.rz.hub.repository.insertion.file :as file]
            [et.rz.hub.repository.homefolder :as home]
            [et.rz.names :as names]
            [et.rz.config :as config]
            [clojure.java.io :as io]
            [et.rz.hub.repository.insertion.arrival :as arrival]
            [et.rz.hub.repository.insertion.unique :as unique]
            [et.rz.hub.repository.insertion.common :as common]))

(defn match? [title] (str/starts-with? title "IMPORT"))

(defn ingest
  [db title _ _]
  (log/info "Starting batch insertion ...")
  (let [extra (str/trim (or (get (str/split title #"IMPORT") 1) ""))
        import-id (common/get-item-or-throw-error db "Imports")
        dir (get-in config/config [:folders :imports])
        ;; Files still on their way -- a temporary name, or one still being
        ;; written -- are left for the next import (arrival), and said.
        listed (home/list-files)
        present (remove arrival/arriving? listed)
        unsettled (arrival/unsettled dir present)]
    (doseq [n (filter arrival/arriving? listed)]
      (log/info (str "Leaving " n " for the next import: a temporary or hidden file")))
    (doseq [n unsettled]
      (log/info (str "Leaving " n " for the next import: it changed while the import looked")))
    (doall (for [file-name (remove unsettled present)]
             (let [orig-file-name file-name
                   wanted (if-not (empty? extra) (str extra " " orig-file-name) orig-file-name)
                   ;; Repaired, on disk (the ren below) and so in the db: NFC,
                   ;; no / or control characters, no spaces at the ends, at
                   ;; most 255 bytes (et.rz.names/repair).
                   file-name (names/repair wanted)]
               (when (not= wanted file-name)
                 (log/info (str "Repairing the name " (pr-str wanted) " -> " (pr-str file-name))))
               ;; Per file: one that cannot be read (Syncthing wrote it 0600 for
               ;; another user, say) is skipped and said, and the import goes
               ;; on with the rest -- it used to end the whole run there, on
               ;; every run.
               (if-let [why (names/unusable file-name)]
                 (log/warn (str "Skipping " (pr-str orig-file-name) " -- " why
                                " (" (pr-str file-name) "); left in imports."))
                 (try
                   (when (home/supported-file-type? file-name)
                     (let [wanted-name file-name
                           ;; Taken already -- in a media folder, by an item, or
                           ;; by another file here: filed as "name (2).ext", the
                           ;; first free number (the owner: "import the second
                           ;; as (2)"; insertion/unique).
                           file-name (unique/free-name db dir orig-file-name file-name)]
                       (when (not= wanted-name file-name)
                         (log/info (str "Numbering the name " (pr-str wanted-name) " -> " (pr-str file-name)
                                        ": the name is taken")))
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
                                    (catch Exception e (log/error (.getMessage e))))))))))
                   (catch java.io.IOException e
                     (log/warn (str "Skipping " orig-file-name " -- it cannot be read ("
                                    (.getMessage e) "); left in imports."))))))))))

(ns config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [datastore.connection :as connection]))

(def ^:private config-path "./config.edn")

;; Every media folder is configured independently under :folders (there is no
;; shared root). :imports is the drop folder the import flow scans; audio/video/
;; docs/images receive files moved out of :imports, classified by suffix;
;; :preview-images receives the previews written by the upload drag-and-drop
;; fields. In prod all are required and must exist (see check-folders); in dev
;; they are hardcoded under ./files/ (see dev-folders).
(def ^:private folder-keys [:imports :audio :video :docs :images :preview-images])
(def ^:private dev-files "./files/")
(def ^:private dev-folders
  {:imports        (str dev-files "Downloads/Tracked/")
   :audio          (str dev-files "Music/Tracked/")
   :video          (str dev-files "Movies/Tracked/")
   :docs           (str dev-files "Documents/Tracked/")
   :images         (str dev-files "Pictures/Tracked/")
   :preview-images (str dev-files "Pictures/Tracked/Preview/")})
(def ^:private dev-dbname  "./rhizome.db")
;; Unit / integration tests run against an in-memory SQLite — no file is
;; created and nothing leaks between runs. Users cannot opt into this via
;; config.edn; it's forced by the :test alias (see test-overrides). To
;; share one in-memory db across multiple JDBC connections we need
;; SQLite's shared-cache URI form rather than the bare ":memory:".
(def ^:private test-dbname "file::memory:?cache=shared")
(def ^:private e2e-dbname  "./test/rhizome-e2e.db")

(defn- e2e-mode? []
  (= "1" (System/getProperty "rhizome.e2e")))

(defn- test-mode? []
  (= "1" (System/getProperty "rhizome.test")))

(def ^:private e2e-overrides
  {:dev?      true
   :e2e?      true
   :bind-host "0.0.0.0"})

(def ^:private test-overrides
  {:dev?  true
   :test? true})

(defn- dev-dbname-for [c]
  (cond (:e2e? c)  e2e-dbname
        (:test? c) test-dbname
        :else      dev-dbname))

(defn- check-mode-flags [c]
  (when (and (:test? c) (:e2e? c))
    (throw (ex-info "config invalid: :test? and :e2e? are mutually exclusive"
                    {:config c})))
  (when (and (or (:test? c) (:e2e? c)) (not (:dev? c)))
    (throw (ex-info "config invalid: :test? / :e2e? require :dev? true"
                    {:config c})))
  c)

(defn- apply-dev-folders [c]
  (if (:dev? c)
    (do
      (doseq [k folder-keys]
        (when (get-in c [:folders k])
          (throw (ex-info
                  (str "config invalid: :folders " k " must not be set when :dev? is true "
                       "(dev mode hardcodes the folders under " dev-files ")")
                  {:config c}))))
      (update c :folders merge dev-folders))
    c))

;; In prod every media folder must be configured and must exist. :images backs
;; /imgs/* (the tracked originals) and :preview-images backs /imgs/Preview/*
;; (generated previews); audio/video/docs/images are the import destinations and
;; :imports is the drop folder the import flow scans. No symlinks are needed.
(defn- check-folders [c]
  (when-not (:dev? c)
    (doseq [k folder-keys]
      (let [dir (get-in c [:folders k])]
        (cond
          (not (string? dir))
          (throw (ex-info (str "config invalid: :folders " k " is required in prod mode")
                          {:config c}))
          (not (.isDirectory (io/file dir)))
          (throw (ex-info (str "config invalid: :folders " k " directory does not exist: " dir)
                          {:config c}))))))
  c)

(defn- resolve-dbname [c]
  (let [hardcoded (when (:dev? c) (dev-dbname-for c))]
    (cond
      hardcoded
      (do (when (:db-path c)
            (throw (ex-info
                    (str "config invalid: :db-path must not be set when :dev? is true "
                         "(hardcoded to " hardcoded ")")
                    {:config c})))
          hardcoded)
      (:db-path c) (:db-path c)
      :else (throw (ex-info "config invalid: :db-path is required in prod mode"
                            {:config c})))))

(defn ds []
  (let [c (aero/read-config config-path)
        c (cond-> c
            (e2e-mode?)  (merge e2e-overrides)
            (test-mode?) (merge test-overrides))
        c (check-mode-flags c)
        c (apply-dev-folders c)
        c (check-folders c)
        dbname (resolve-dbname c)]
    (-> c
        (dissoc :db-path)
        (assoc :db (connection/make-datasource {:dbname dbname})))))

(def config (ds))

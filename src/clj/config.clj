(ns config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [datastore.connection :as connection])
  (:import [ch.qos.logback.classic LoggerContext]
           [ch.qos.logback.classic.joran JoranConfigurator]
           [org.slf4j LoggerFactory]))

(def ^:private config-path "./config.edn")

;; Every media folder is configured independently under :folders (there is no
;; shared root). :imports is the drop folder the import flow scans; audio/video/
;; docs/images receive files moved out of :imports, classified by suffix;
;; :preview-images receives the previews written by the upload drag-and-drop
;; fields. In prod all must be configured (see check-folders); in dev they are
;; hardcoded under ./files/ (see dev-folders). On-disk existence is checked
;; separately at startup, not here -- see server/check-folders-exist!, which
;; runs once logging is configured (config load must stay logging-dependency-
;; free, see log-init).
(def folder-keys [:imports :audio :video :docs :images :preview-images])
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

;; In prod every media folder must be configured. :images backs /imgs/* (the
;; tracked originals) and :preview-images backs /imgs/Preview/* (generated
;; previews); audio/video/docs/images are the import destinations and :imports
;; is the drop folder the import flow scans. No symlinks are needed. This only
;; validates that a path is configured -- on-disk existence is checked at
;; startup by server/check-folders-exist! (after logging is up), so a missing
;; folder can warn/fail with proper logging rather than blowing up config load.
(defn- check-folders [c]
  (when-not (:dev? c)
    (doseq [k folder-keys]
      (when-not (string? (get-in c [:folders k]))
        (throw (ex-info (str "config invalid: :folders " k " is required in prod mode")
                        {:config c})))))
  c)

;; The logs directory is the one folder that isn't a hard requirement: it's
;; configurable in prod via :folders :logs, hardcoded in dev (like the media
;; folders), and falls back to "logs" when unset. logback.xml reads it from the
;; LOGS_DIR system property (${LOGS_DIR:-logs}).
(def ^:private default-logs-dir "logs")

(defn- configure-logging!
  "Point logback at `logs-dir` via the LOGS_DIR property. Logging may already
   have initialised against the default, so reset + re-read logback.xml to make
   the property take effect regardless of init order."
  [logs-dir]
  (System/setProperty "LOGS_DIR" logs-dir)
  (let [ctx (LoggerFactory/getILoggerFactory)]
    (when (instance? LoggerContext ctx)
      ;; reset + re-read is logback's own reconfigure idiom (same as auto-scan):
      ;; .reset clears the appenders configured against the old dir, doConfigure
      ;; rebuilds them with LOGS_DIR now set. Lines already written stay written.
      (.reset ^LoggerContext ctx)
      (doto (JoranConfigurator.)
        (.setContext ctx)
        (.doConfigure (io/resource "logback.xml"))))))

(defn- apply-logs-dir [c]
  (when (and (:dev? c) (get-in c [:folders :logs]))
    (throw (ex-info (str "config invalid: :folders :logs must not be set when :dev? is true "
                         "(dev mode hardcodes it to " default-logs-dir ")")
                    {:config c})))
  (let [dir (if (:dev? c)
              default-logs-dir
              (or (get-in c [:folders :logs]) default-logs-dir))]
    (configure-logging! dir)
    (assoc-in c [:folders :logs] dir)))

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
        c (apply-logs-dir c)
        dbname (resolve-dbname c)]
    (-> c
        (dissoc :db-path)
        (assoc :db (connection/make-datasource {:dbname dbname})))))

(def config (ds))

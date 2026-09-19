(ns et.rz.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [et.rz.hub.sqlite.connection :as connection]
            [et.rz.role :as role])
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
;; Unit / integration tests run against an in-memory SQLite — no file is
;; created and nothing leaks between runs. Users cannot opt into this via
;; config.edn; it's forced by the :test alias (see test-overrides). To
;; share one in-memory db across multiple JDBC connections we need
;; SQLite's shared-cache URI form rather than the bare ":memory:".
;;
;; **The only db path this process still knows.** The dev and e2e paths that
;; used to sit beside it are the db-server's business since the split -- they
;; are `:db-server :db-path` in config.edn, written by onboard.sh as
;; `#or [#env DB_PATH "./rhizome.db"]`, which is how `scripts/e2e.sh` points
;; its own db-server at ./test/rhizome-e2e.db without a second config file.
;; This one cannot follow them there, and that is not an oversight: a
;; shared-cache in-memory SQLite lives inside one JVM, so no separate process
;; could open it. See the `:db` handle in `ds` below.
(def ^:private test-dbname "file::memory:?cache=shared")

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

;; --- which machine holds the database ---------------------------------------
;; The marker lives in `role`, which the db-server can require and this
;; namespace cannot be required from (loading `config` builds the app's whole
;; configuration, folders and all, out of a file the db-server may not even
;; share). Since step 4 it elects the hub rather than demoting replicas --
;; see `role`, and `db-server/check-elected!`, which is the one reader.
;;
;; Re-exported here because config_test and the scripts have always called
;; them this.
(def primary-marker role/primary-marker)
(def primary-marker-present? role/primary-marker-present?)

;; --- the database, which is not in this process any more --------------------
;; Two keys moved into the `:db-server` section when the db-server became its
;; own process, and both would fail *silently* if a config.edn from before the
;; split were simply read: a top-level `:db-path` would be ignored by an app
;; that no longer opens a file at all, and a `:semsearch :vec-path` would leave
;; `et.rz.hub.sqlite.connection` with no extension path -- semantic search quietly off
;; and the ^:vector tests quietly skipped. So they are refused, by name, with
;; the move spelled out.
(defn- check-moved-keys [c]
  (when (:db-path c)
    (throw (ex-info (str "config invalid: :db-path moved into the :db-server section. "
                         "The app-server holds no database; the db-server opens the file. "
                         "Write :db-server {:db-path \"…\"} instead.")
                    {:config c})))
  (when (get-in c [:semsearch :vec-path])
    (throw (ex-info (str "config invalid: :vec-path moved from :semsearch into the "
                         ":db-server section. Loading the sqlite-vec extension is the "
                         "db-server's business now; :semsearch keeps :ollama-url and "
                         ":ollama-model, which are the app-side embedder's.")
                    {:config c})))
  c)

(defn- db-url
  "Where this app-server's db-server is. `:db-url` wins when it is set -- that
   is the separate-files arrangement, and the hook the later remote-machine
   step needs -- otherwise it is derived from the `:db-server :port` in the
   file both processes share.

   Neither present is a refusal rather than a default. A guessed port would
   come up green and fail on the first statement with a connection refused,
   which is the confusing version of exactly this message."
  [c]
  (or (:db-url c)
      (when-let [port (get-in c [:db-server :port])]
        (str "http://127.0.0.1:" port))
      (throw (ex-info (if (contains? c :db-server)
                        ;; There IS a section; it just does not name a port. Saying
                        ;; "no :db-server section" here was simply false, and a false
                        ;; message costs more than a missing one -- it sends the reader
                        ;; to look for something that is in front of them.
                        (str "config invalid: the :db-server section has no :port, so there "
                             "is nothing to derive this app-server's db handle from. Add "
                             ":port to it (that is what `make onboard` writes), or set a "
                             "top-level :db-url. Note that the db-server defaults its own "
                             "port when the section omits one -- this process does not "
                             "guess at it, because a guess that happened to be wrong would "
                             "come up green and fail on the first statement.")
                        (str "config invalid: no :db-server section and no :db-url. "
                             "The app-server reaches its database over HTTP, so it needs "
                             "either :db-server {:port …} to derive http://127.0.0.1:<port> "
                             "from, or an explicit :db-url. `make onboard` writes the "
                             "section; a config.edn from before the split has to gain it."))
                      {:config c}))))

(defn- db-handle
  "The `:db` every call site is given.

   **Test mode is the one arrangement that keeps a local DataSource, and it is
   not a shortcut.** The test database is a shared-cache in-memory SQLite, which
   lives inside one JVM: no separate process could open it, so there is no
   db-server for this handle to point at. The one the integration suites run
   against is booted *in* the test JVM by `db-harness`, on an ephemeral port,
   against this very datasource's file name -- and every test's own setup
   statements and assertions go on using this handle directly. That is the
   two-names-onto-one-database decision from step 3, and 88 statements across 19
   files rest on `(:db config/config)` still being a DataSource here.

   **Everywhere else -- dev, e2e, prod -- it is nil**, and that is deliberate.
   Since step 4 this process does not reach the database at all: the hub answers
   `/ui` and `/api`, and what is left here is the frontend, this machine's files
   and the forwarding. There is no handle to give, and `nil` is the honest value
   for that. A leftover call site that still expects one fails at once and says
   which line it was on -- where a remote handle would have gone on quietly
   speaking SQL over a wire that is being retired.

   The hub's address is `:hub-url`, a separate key, because it is a separate
   fact: where the hub is, not what this process may query."
  [c]
  (when (:test? c)
    (connection/make-datasource {:dbname test-dbname :read-only? false})))

(defn ds []
  (let [c (aero/read-config config-path)
        c (cond-> c
            (e2e-mode?)  (merge e2e-overrides)
            (test-mode?) (merge test-overrides))
        c (check-mode-flags c)
        c (check-moved-keys c)
        c (apply-dev-folders c)
        c (check-folders c)
        c (apply-logs-dir c)]
    (-> c
        (assoc :db (db-handle c))
        ;; Where the hub is. Nil in test mode, which is the one arrangement
        ;; with no hub to talk to -- `hub-proxy` reads exactly this to decide
        ;; whether to forward, so "no hub" and "answer it here" are one fact.
        (assoc :hub-url (when-not (:test? c) (db-url c))))))

(def config (ds))

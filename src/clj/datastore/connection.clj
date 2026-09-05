(ns datastore.connection
  (:require [aero.core :as aero]
            [clojure.string :as str])
  (:import [java.io File]
           [javax.sql DataSource]
           [java.sql Connection]
           [org.sqlite SQLiteConfig SQLiteConfig$TransactionMode SQLiteDataSource]))

(defn- read-config* []
  ;; Peek at config.edn directly so we don't pull in the `config` ns
  ;; (which depends on this one). The `:db-server` block is the single
  ;; source of truth: when present, it carries the vec-extension path; when
  ;; absent, the sqlite-vec extension stays off.
  ;;
  ;; The key sat under `:semsearch` until the split. It moved because loading
  ;; the extension is the *database's* business and `:semsearch` is the app-side
  ;; embedder's -- ollama's url and model stay there. A config.edn still
  ;; carrying the old key is refused by name, loudly, in `config` and in
  ;; `db-server`'s `-main`; the peek here stays deliberately silent about a
  ;; missing or unreadable file, because unit tests and tools load this
  ;; namespace with no config.edn at all.
  (try (aero/read-config "./config.edn")
       (catch Exception _ nil)))

(def vec-extension-path
  (some-> (read-config*) :db-server :vec-path))

(defn- vec-extension-file ^File []
  (when vec-extension-path
    (let [os  (System/getProperty "os.name")
          ext (cond (.contains os "Mac")   ".dylib"
                    (.contains os "Linux") ".so"
                    :else                  ".dylib")]
      (File. (str vec-extension-path ext)))))

(def vec-available?
  "True iff `:db-server :vec-path` is in config AND the configured
   vec-extension dylib actually exists on disk. Omit it (or point it at a
   missing file) and both the db-server and the test runner treat the vec
   extension as absent."
  (boolean (some-> (vec-extension-file) .exists)))

(defn- load-vec! [^Connection c]
  (with-open [s (.createStatement c)]
    (.execute s (str "SELECT load_extension('" vec-extension-path "')")))
  c)

;; Pinned connections for shared-cache in-memory test DBs. SQLite drops the
;; in-memory database the moment its last connection closes, which would
;; tear down the schema between the test runner's `apply-schema!` call and
;; the first query. Holding one Connection per datasource for the JVM's
;; lifetime keeps the shared-cache DB alive without changing how callers
;; borrow connections.
(defonce ^:private anchor-connections (atom []))

(defn- pin-in-memory-anchor! [^DataSource ds ^String dbname]
  (when (str/starts-with? dbname "file::memory:")
    (swap! anchor-connections conj (.getConnection ds))))

(defn- vec-loading-datasource
  "Delegating wrapper that loads the vec extension on every borrowed
   connection. Must not be a `proxy`: `proxy-super` strips the overridden
   method off the shared proxy instance and restores it in a `finally`, so
   two threads borrowing at once can leave the override permanently nil,
   after which all items_vec SQL fails with \"no such module: vec0\" until
   the process restarts."
  ^DataSource [^SQLiteDataSource inner]
  (reify DataSource
    (getConnection [_] (load-vec! (.getConnection inner)))
    (getConnection [_ u p] (load-vec! (.getConnection inner u p)))
    (getLogWriter [_] (.getLogWriter inner))
    (setLogWriter [_ out] (.setLogWriter inner out))
    (getLoginTimeout [_] (.getLoginTimeout inner))
    (setLoginTimeout [_ seconds] (.setLoginTimeout inner seconds))
    (getParentLogger [_] (.getParentLogger inner))
    (unwrap [_ iface] (if (.isInstance ^Class iface inner) inner (.unwrap inner iface)))
    (isWrapperFor [_ iface] (or (.isInstance ^Class iface inner)
                                (.isWrapperFor inner iface)))))

(defn make-datasource
  "Wrap a SQLite db spec as a DataSource that loads the sqlite-vec
   extension on every connection (when present). The extension path is
   read from `:db-server :vec-path` in config.edn; if it is absent or the
   dylib is missing, the datasource still works but vector-search features
   (items_vec / vec_distance_*) are unavailable.

   With `:read-only? true` the db file is opened in SQLite's read-only open
   mode (sqlite-jdbc's SQLiteConfig/setReadOnly, i.e. SQLITE_OPEN_READONLY
   rather than a query_only pragma the connection could clear): reads work,
   every INSERT/UPDATE/DELETE fails with SQLITE_READONLY, on every connection
   borrowed from this datasource. That is how a read-only replica's write ban
   is made structural -- see config/read-only-replica?. Note that read-only
   mode never creates the file, so the db has to exist already."
  ^DataSource [{:keys [dbname read-only?]}]
  (let [cfg   (doto (SQLiteConfig.)
                (.enableLoadExtension vec-available?)
                (.setReadOnly (boolean read-only?))
                ;; Write transactions take their lock at BEGIN rather than on
                ;; their first write. Every transaction here reads before it
                ;; writes -- the acyclicity check before the relation rows, the
                ;; deletion plan before the deletes -- and a deferred BEGIN takes
                ;; only a shared lock for that read, so the write has to upgrade.
                ;; SQLite refuses that upgrade outright when another connection
                ;; is writing, *without* consulting the busy handler, because
                ;; waiting there could deadlock: measured, a contended save fails
                ;; in 4ms and the 3s busy_timeout is never spent. Taking the
                ;; write lock up front is the case the busy handler does serve,
                ;; so the wait actually happens -- and the read then sees a state
                ;; no other writer can move before the write lands, which is what
                ;; the check needs to mean anything.
                ;;
                ;; Not on a replica: its transactions can only ever read, and an
                ;; immediate BEGIN would ask a read-only database for a write
                ;; lock.
                (.setTransactionMode (if read-only?
                                       SQLiteConfig$TransactionMode/DEFERRED
                                       SQLiteConfig$TransactionMode/IMMEDIATE)))
        inner (doto (SQLiteDataSource. cfg)
                (.setUrl (str "jdbc:sqlite:" dbname)))
        ds    (if vec-available?
                (vec-loading-datasource inner)
                inner)]
    (pin-in-memory-anchor! ds dbname)
    ds))

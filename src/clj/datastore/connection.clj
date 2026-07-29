(ns datastore.connection
  (:require [aero.core :as aero]
            [clojure.string :as str])
  (:import [java.io File]
           [javax.sql DataSource]
           [java.sql Connection]
           [org.sqlite SQLiteConfig SQLiteDataSource]))

(defn- read-config* []
  ;; Peek at config.edn directly so we don't pull in the `config` ns
  ;; (which depends on this one). The `:semsearch` block is the single
  ;; source of truth: when present, it carries the vec-extension path; when
  ;; absent, semantic search and the sqlite-vec extension stay off.
  (try (aero/read-config "./config.edn")
       (catch Exception _ nil)))

(def vec-extension-path
  (some-> (read-config*) :semsearch :vec-path))

(defn- vec-extension-file ^File []
  (when vec-extension-path
    (let [os  (System/getProperty "os.name")
          ext (cond (.contains os "Mac")   ".dylib"
                    (.contains os "Linux") ".so"
                    :else                  ".dylib")]
      (File. (str vec-extension-path ext)))))

(def vec-available?
  "True iff `:semsearch` is in config AND the configured vec-extension
   dylib actually exists on disk. Omit `:semsearch` (or point its
   `:vec-path` at a missing file) and both the app and the test runner
   treat semantic search as off."
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
   read from `:semsearch :vec-path` in config.edn; if `:semsearch` is
   absent or the dylib is missing, the datasource still works but
   vector-search features (items_vec / vec_distance_*) are unavailable.

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
                (.setReadOnly (boolean read-only?)))
        inner (doto (SQLiteDataSource. cfg)
                (.setUrl (str "jdbc:sqlite:" dbname)))
        ds    (if vec-available?
                (vec-loading-datasource inner)
                inner)]
    (pin-in-memory-anchor! ds dbname)
    ds))

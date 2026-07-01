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

(defn- pin-in-memory-anchor! [^SQLiteDataSource ds ^String dbname]
  (when (str/starts-with? dbname "file::memory:")
    (swap! anchor-connections conj (.getConnection ds))))

(defn make-datasource
  "Wrap a SQLite db spec as a DataSource that loads the sqlite-vec
   extension on every connection (when present). The extension path is
   read from `:semsearch :vec-path` in config.edn; if `:semsearch` is
   absent or the dylib is missing, the datasource still works but
   vector-search features (items_vec / vec_distance_*) are unavailable."
  ^DataSource [{:keys [dbname]}]
  (let [cfg (doto (SQLiteConfig.)
              (.enableLoadExtension vec-available?))
        ds  (if vec-available?
              (proxy [SQLiteDataSource] [cfg]
                (getConnection
                  ([] (load-vec! (proxy-super getConnection)))
                  ([u p] (load-vec! (proxy-super getConnection u p)))))
              (SQLiteDataSource. cfg))]
    (.setUrl ^SQLiteDataSource ds (str "jdbc:sqlite:" dbname))
    (pin-in-memory-anchor! ds dbname)
    ds))

(ns datastore.connection
  (:require [clojure.edn :as edn])
  (:import [java.io File]
           [javax.sql DataSource]
           [java.sql Connection]
           [org.sqlite SQLiteConfig SQLiteDataSource]))

(defn vec-extension-path []
  (or (System/getenv "SQLITE_VEC_PATH")
      (let [os (System/getProperty "os.name")]
        (cond
          (.contains os "Mac")     "./.sqlite-vec/vec0"
          (.contains os "Linux")   "/usr/local/lib/sqlite-vec/vec0"
          :else                    "./.sqlite-vec/vec0"))))

(defn- vec-extension-file ^File []
  (let [base (vec-extension-path)
        os   (System/getProperty "os.name")
        ext  (cond (.contains os "Mac") ".dylib"
                   (.contains os "Linux") ".so"
                   :else ".dylib")]
    (File. (str base ext))))

(defn- semsearch-configured?
  "Peek at the config file directly (bypassing the `config` ns to avoid a
   require cycle) and return true iff `:semsearch` is present. Unknown
   EDN tag readers are stubbed so we don't have to mirror `config`'s
   reader set just for a presence check."
  []
  (try
    (let [path (or (System/getenv "RHIZOME_CONFIG") "./config.edn")
          readers {'env (constantly nil) 'or (constantly nil)}]
      (some? (:semsearch (edn/read-string {:readers readers} (slurp path)))))
    (catch Exception _ false)))

(def vec-available?
  "True iff the sqlite-vec extension file is on disk AND `:semsearch` is
   configured. Without `:semsearch`, semantic search is treated as off
   regardless of whether the extension dylib is present."
  (and (.exists (vec-extension-file))
       (semsearch-configured?)))

(defn- load-vec! [^Connection c]
  (with-open [s (.createStatement c)]
    (.execute s (str "SELECT load_extension('" (vec-extension-path) "')")))
  c)

(defn make-datasource
  "Wrap a SQLite db spec as a DataSource that loads the sqlite-vec
   extension on every connection (when present). The path is taken from
   SQLITE_VEC_PATH or a platform default. If the extension file is
   missing, the datasource still works but vector-search features
   (items_vec / vec_distance_*) are unavailable."
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
    ds))

(ns datastore.connection
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

(def vec-available?
  "True iff the sqlite-vec extension file is present on disk."
  (.exists (vec-extension-file)))

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

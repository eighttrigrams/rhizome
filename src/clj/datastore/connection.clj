(ns datastore.connection
  (:import [javax.sql DataSource]
           [java.sql Connection]
           [org.sqlite SQLiteConfig SQLiteDataSource]))

(defn vec-extension-path []
  (or (System/getenv "SQLITE_VEC_PATH")
      (let [os (System/getProperty "os.name")]
        (cond
          (.contains os "Mac")     "./.sqlite-vec/vec0"
          (.contains os "Linux")   "/usr/local/lib/sqlite-vec/vec0"
          :else                    "./.sqlite-vec/vec0"))))

(defn- load-vec! [^Connection c]
  (with-open [s (.createStatement c)]
    (.execute s (str "SELECT load_extension('" (vec-extension-path) "')")))
  c)

(defn make-datasource
  "Wrap a SQLite db spec as a DataSource that loads the sqlite-vec
   extension on every connection. The path is taken from
   SQLITE_VEC_PATH or a platform default."
  ^DataSource [{:keys [dbname]}]
  (let [cfg (doto (SQLiteConfig.)
              (.enableLoadExtension true))
        ds  (proxy [SQLiteDataSource] [cfg]
              (getConnection
                ([] (load-vec! (proxy-super getConnection)))
                ([u p] (load-vec! (proxy-super getConnection u p)))))]
    (.setUrl ^SQLiteDataSource ds (str "jdbc:sqlite:" dbname))
    ds))

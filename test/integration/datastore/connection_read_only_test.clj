(ns datastore.connection-read-only-test
  "The structural half of read-only replica mode: a datasource built with
   :read-only? true cannot write, whatever the caller does. This is the
   connection layer on its own -- no prod-mode scaffolding involved."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [datastore.connection :as connection])
  (:import [java.io File]))

(defn- temp-db-path
  "A unique path that does not exist yet -- sqlite creates the file on the first
   read-write connection."
  []
  (let [f (File/createTempFile "rhizome-read-only" ".db")]
    (.delete f)
    (.getPath f)))

(defn- delete-db! [path]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (.delete (File. (str path suffix)))))

(defn- write-error
  [ds sql]
  (try (jdbc/execute-one! ds [sql]) nil (catch java.sql.SQLException e e)))

(deftest read-only-datasource-refuses-every-write-test
  (let [path (temp-db-path)]
    (try
      (let [rw (connection/make-datasource {:dbname path})]
        (jdbc/execute-one! rw ["CREATE TABLE t (a INTEGER)"])
        (jdbc/execute-one! rw ["INSERT INTO t (a) VALUES (1)"]))
      (let [ro (connection/make-datasource {:dbname path :read-only? true})]
        (testing "reads go through"
          (is (= 1 (:t/a (jdbc/execute-one! ro ["SELECT a FROM t"])))))
        (testing "writes fail inside sqlite, not by convention"
          (doseq [sql ["INSERT INTO t (a) VALUES (2)"
                       "UPDATE t SET a = 3"
                       "DELETE FROM t"
                       "CREATE TABLE u (b INTEGER)"]]
            (let [e (write-error ro sql)]
              (is (some? e) (str "expected to be refused: " sql))
              (is (re-find #"(?i)readonly" (.getMessage e))
                  (str "expected a SQLITE_READONLY message for: " sql)))))
        (testing "nothing landed"
          (is (= 1 (:n (jdbc/execute-one! ro ["SELECT count(*) AS n FROM t"]))))))
      (testing "the open mode is what refuses -- the same file is writable through a normal datasource"
        (let [rw (connection/make-datasource {:dbname path})]
          (is (nil? (write-error rw "INSERT INTO t (a) VALUES (5)")))
          (is (= 2 (:n (jdbc/execute-one! rw ["SELECT count(*) AS n FROM t"]))))))
      (finally (delete-db! path)))))

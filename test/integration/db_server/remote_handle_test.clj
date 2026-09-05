(ns db-server.remote-handle-test
  "The facade's other half: a `db` handle that names a db-server over HTTP
   instead of a DataSource.

   What these pin is not that the protocol works -- db-server.protocol-test
   does that, by hand, without this client -- but that a caller cannot tell the
   difference. The same call against a local handle and against a remote one has
   to answer the same value and fail the same way, because every call site above
   the seam is written once and has to work either way.

   So most of what is here runs a script twice, against two databases that
   differ only in which side of a wire they are on, and compares."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datastore.connection :as connection]
            [db :as db]
            [db-server])
  (:import [java.sql SQLException]))

(defn- temp-db-path []
  (.getAbsolutePath (doto (java.io.File/createTempFile "rhizome-remote-handle-test" ".db")
                      (.deleteOnExit))))

(defn- with-remote
  "Call `f` with a remote handle on a freshly booted db-server."
  ([f] (with-remote {} f))
  ([opts f]
   (let [server (db-server/start! (merge {:port 0 :db-path (temp-db-path)} opts))]
     (try (f {:db-server/url (:url server)} server)
          (finally (db-server/stop! server))))))

(defn- local-handle [] (connection/make-datasource {:dbname (temp-db-path)}))

(defn- rows [handle] (mapv :t/n (db/execute! handle ["SELECT n FROM t ORDER BY n"])))

;; -- the same answers ------------------------------------------------------

(defn- script
  "Everything the seam carries, run against whatever handle it is given, with
   each step's answer collected. Two handles running this have to agree."
  [handle]
  [(db/execute-one! handle ["CREATE TABLE t (n INTEGER, s TEXT)"])
   (db/execute-one! handle ["INSERT INTO t (n, s) VALUES (?, ?)" 1 "it's quoted"])
   (db/execute-one! handle ["INSERT INTO t (n, s) VALUES (?, ?)" 2 "two"] {:return-keys true})
   (db/execute! handle ["SELECT n, s FROM t ORDER BY n"])
   (db/execute-one! handle ["SELECT n AS MiXeD FROM t WHERE n = 1"] {:builder :unqualified-lower})
   (db/execute-one! handle ["SELECT n FROM t WHERE n = 99"])
   (db/execute! handle ["SELECT n FROM t WHERE n = 99"])
   (db/with-transaction [tx handle]
     (db/execute-one! tx ["INSERT INTO t (n, s) VALUES (?, ?)" 3 "three"])
     (db/execute! tx ["SELECT n FROM t ORDER BY n"]))
   (db/execute! handle ["SELECT n FROM t ORDER BY n"])])

(deftest a-remote-handle-answers-what-a-local-one-answers
  (with-remote
    (fn [remote _server]
      (let [local (local-handle)]
        (is (= (script local) (script remote))
            "statements, parameters, both builders, a nil row, an empty result and a transaction")))))

(deftest a-transaction-that-throws-rolls-back-over-the-wire-too
  (with-remote
    (fn [remote _server]
      (let [local (local-handle)]
        (doseq [[label handle] [["local" local] ["remote" remote]]]
          (testing label
            (db/execute-one! handle ["CREATE TABLE t (n INTEGER)"])
            (db/with-transaction [tx handle] (db/execute-one! tx ["INSERT INTO t (n) VALUES (1)"]))
            (is (= [1] (rows handle)) "the transaction that returned committed")
            (is (thrown? clojure.lang.ExceptionInfo
                         (db/with-transaction [tx handle]
                           (db/execute-one! tx ["INSERT INTO t (n) VALUES (2)"])
                           (throw (ex-info "the body failed" {})))))
            (is (= [1] (rows handle)) "and the one that threw rolled back")))))))

(deftest nesting-is-refused-identically-on-both-sides-of-the-wire
  ;; The whole point of `transact` binding *nested-tx* to :prohibit was to make
  ;; "a handle that is already a transaction may not be made one again" a rule
  ;; rather than a silent early commit. next.jdbc enforces it locally by
  ;; Connection identity, which does not survive serialization, so the remote
  ;; half enforces it itself -- and has to fail the same way, or the rule would
  ;; be two rules wearing one name.
  (with-remote
    (fn [remote _server]
      (let [local (local-handle)]
        (doseq [[label handle] [["local" local] ["remote" remote]]]
          (testing label
            (db/execute-one! handle ["CREATE TABLE t (n INTEGER)"])
            (let [thrown (try (db/with-transaction [outer handle]
                                (db/execute-one! outer ["INSERT INTO t (n) VALUES (1)"])
                                (db/with-transaction [inner outer]
                                  (db/execute-one! inner ["INSERT INTO t (n) VALUES (2)"])))
                              nil
                              (catch Throwable t t))]
              (is (instance? IllegalStateException thrown))
              (is (= "Nested transactions are prohibited" (.getMessage thrown)))
              (is (= [] (rows handle))
                  "and the outer one rolled back rather than committing early"))))))))

(deftest a-statement-the-database-refuses-is-a-SQLException-on-both-sides
  (with-remote
    (fn [remote _server]
      (let [local (local-handle)]
        (doseq [[label handle] [["local" local] ["remote" remote]]]
          (testing label
            (let [thrown (try (db/execute! handle ["SELECT * FROM nope"])
                              nil
                              (catch Throwable t t))]
              (is (instance? SQLException thrown)
                  "a caller that discriminates on the type cannot tell which side it is on")
              (is (str/includes? (.getMessage thrown) "no such table")))))))))

(deftest an-option-off-the-whitelist-is-refused-before-it-is-sent
  (with-remote
    (fn [remote _server]
      (let [local (local-handle)
            message (fn [handle opts]
                      (try (db/execute! handle ["SELECT 1"] opts)
                           nil
                           (catch clojure.lang.ExceptionInfo e (.getMessage e))))]
        (testing "the same refusal, in the same words, wherever the handle points"
          (is (= (message local {:timeout 5}) (message remote {:timeout 5})))
          (is (= (message local {:builder :qualified-kebab})
                 (message remote {:builder :qualified-kebab}))))
        (is (re-find #"unsupported statement option" (message remote {:timeout 5}))
            "and it is the facade's own message, so this never reached the wire")))))

;; -- what only a remote handle has ----------------------------------------

(deftest vec-availability-comes-off-the-db-servers-health
  (with-remote
    (fn [remote _server]
      (testing "it answers, and agrees with the process actually holding the extension"
        ;; One JVM here, so `datastore.connection`'s answer IS the db-server's.
        ;; That agreement is necessary but not sufficient -- it would hold just
        ;; as well if the remote branch read the app-side def and never asked --
        ;; so the next assertion is the one that discriminates.
        (is (boolean? (db/vec-available? remote)))
        (is (= connection/vec-available? (db/vec-available? remote))))
      (testing "asked twice, answered the same"
        (is (= (db/vec-available? remote) (db/vec-available? remote))))))
  (testing "and it is genuinely fetched: a handle with no db-server behind it cannot answer"
    ;; Port 9 is discard; nothing in this box binds it, so the connection is
    ;; refused. A local handle answers this question off a def and can never
    ;; fail, which is exactly the difference being pinned: after the split the
    ;; dylib is on the far side of the wire and the app does not get to guess.
    (is (thrown? Exception (db/vec-available? {:db-server/url "http://127.0.0.1:9"})))))

(deftest a-token-that-names-nothing-is-a-clear-refusal
  (with-remote
    (fn [remote _server]
      (let [thrown (try (db/execute! (assoc remote :db-server/tx "not-a-token") ["SELECT 1"])
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (some? thrown))
        (is (= :db-server/unknown-transaction (:type (ex-data thrown))))
        (is (= 410 (:status (ex-data thrown))))
        (is (re-find #"no such transaction" (.getMessage thrown)))))))

(deftest the-handle-a-transaction-hands-its-body-carries-the-token
  (with-remote
    (fn [remote _server]
      (db/execute-one! remote ["CREATE TABLE t (n INTEGER)"])
      (db/with-transaction [tx remote]
        (is (= (:db-server/url remote) (:db-server/url tx)))
        (is (string? (:db-server/tx tx))
            "which is what makes it a different handle from the one it was opened on")
        (is (nil? (:db-server/tx remote))
            "and leaves the outer handle exactly as it was")))))

;; -- the arrangement step 3 will use --------------------------------------

(deftest a-db-server-serves-the-shared-in-memory-database-the-suite-runs-on
  ;; Step 3 boots the db-server in-process against the same shared-cache
  ;; in-memory SQLite the tests already use, and hands the app a remote handle
  ;; onto it. This is that arrangement, proven early and in one test.
  ;;
  ;; The dbname matters and is not interchangeable with any other spelling of
  ;; "in memory": `datastore.connection` pins an anchor connection for exactly
  ;; the `file::memory:` prefix, and without one SQLite drops the database the
  ;; moment the last borrowed connection closes -- which here would be between
  ;; the CREATE and the SELECT.
  (let [server (db-server/start! {:port 0 :db-path "file::memory:?cache=shared"})
        remote {:db-server/url (:url server)}]
    (try
      (db/execute-one! remote ["CREATE TABLE IF NOT EXISTS probe (n INTEGER)"])
      (db/execute-one! remote ["DELETE FROM probe"])
      (db/with-transaction [tx remote]
        (db/execute-one! tx ["INSERT INTO probe (n) VALUES (1)"]))
      (is (= [1] (mapv :probe/n (db/execute! remote ["SELECT n FROM probe"])))
          "written through the wire and read back out of the same in-memory database")
      (testing "and the suite's own local handle is looking at that very database"
        (is (= [1] (mapv :probe/n (db/execute! (:ds server) ["SELECT n FROM probe"])))))
      (finally
        (try (db/execute-one! remote ["DROP TABLE IF EXISTS probe"]) (catch Throwable _))
        (db-server/stop! server)))))

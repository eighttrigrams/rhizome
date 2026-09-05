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
            [config :as config]
            [datastore.connection :as connection]
            [db :as db]
            [db-harness]
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

(defn- wait-for
  "Poll `pred` until it answers truthy or `ms` runs out, and answer what it last
   saw. Bounded on purpose: an unbounded wait on a condition a regression has
   just broken is a test suite that hangs instead of failing."
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [v (pred)]
        (if (or v (> (System/currentTimeMillis) deadline))
          v
          (do (Thread/sleep 50) (recur)))))))

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

(deftest what-equality-cannot-see-between-the-two-handles
  ;; The parity test above compares with `=`, and `=` is blind to two real
  ;; differences. Naming them here is the point: they are acceptable, and a
  ;; reader should not have to discover that they exist.
  (with-remote
    (fn [remote _server]
      (let [local (local-handle)]
        (doseq [h [local remote]]
          (db/execute-one! h ["CREATE TABLE t (n INTEGER, d REAL, s TEXT)"])
          (db/execute-one! h ["INSERT INTO t (n, d, s) VALUES (?, ?, ?)" 42 1.5 "x"]))
        (let [l (db/execute-one! local ["SELECT n, d, s FROM t"])
              r (db/execute-one! remote ["SELECT n, d, s FROM t"])]
          (is (= l r) "equal, which is what every caller asks")
          (testing "an integer widens on the way through transit"
            (is (instance? Integer (:t/n l)))
            (is (instance? Long (:t/n r)))
            (is (= (:t/n l) (:t/n r))
                ;; Harmless because nothing reads these through Java interop:
                ;; = , arithmetic, zero? and number? do not distinguish the two,
                ;; and the /ui path serializes them through transit anyway.
                "which Clojure's = does not distinguish, and nothing here does either"))
          (testing "doubles and strings come back as themselves"
            (is (instance? Double (:t/d r)))
            (is (instance? String (:t/s r))))
          (testing "and next.jdbc's row metadata does not survive the wire"
            ;; datafy/nav, which make a row navigable at a REPL. Keyed by
            ;; SYMBOL and not keyword -- next.jdbc attaches them syntax-quoted,
            ;; which is the convention those two protocols are looked up by.
            ;; Nothing in rhizome reads them, and they are functions, which is
            ;; the one thing that cannot be sent.
            (is (some? (get (meta l) 'clojure.core.protocols/datafy)))
            (is (some? (get (meta l) 'clojure.core.protocols/nav)))
            (is (nil? (meta r)))))))))

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

(deftest a-commit-the-database-refuses-is-a-SQLException-and-not-a-story-about-rollback
  ;; The commit used to be inside the body's catch, so a refused COMMIT was
  ;; followed by a compensating /tx/rollback, which 410'd because the failed
  ;; commit had already freed the token -- and the caller was handed
  ;; `Rollback failed handling "…"`, an ExceptionInfo asserting something that
  ;; had not happened, in place of the database's own refusal.
  (let [path (temp-db-path)]
    (let [ds (connection/make-datasource {:dbname path})]
      (db/execute-one! ds ["CREATE TABLE t (n INTEGER)"]))
    (let [server (db-server/start! {:port 0 :db-path path})
          remote {:db-server/url (:url server)}
          local  (connection/make-datasource {:dbname path})
          ;; A DEFERRED reader holding SHARED: a writer may begin and insert,
          ;; and is refused when its COMMIT reaches for EXCLUSIVE.
          reader (connection/make-datasource {:dbname path :read-only? true})
          rc     (.getConnection reader)
          refuse (fn [handle]
                   (try (db/with-transaction [tx handle]
                          (db/execute-one! tx ["INSERT INTO t (n) VALUES (1)"]))
                        nil
                        (catch Throwable t t)))]
      (try
        (.setAutoCommit rc false)
        (db/execute! rc ["SELECT n FROM t"])
        (let [l (refuse local)
              r (refuse remote)]
          (is (instance? SQLException l) "locally the database's refusal comes out as it is")
          (is (instance? SQLException r) "and it has to come out as it is remotely too")
          (is (= (.getMessage l) (.getMessage r)) "with the message the driver wrote")
          (is (not (instance? clojure.lang.ExceptionInfo r))
              "and not as a claim about a rollback that never failed")
          (is (= (.getErrorCode l) (.getErrorCode r)) "the driver's own vendor code")
          (is (= (.getSQLState l) (.getSQLState r)) "and its SQLSTATE"))
        (finally
          (try (.rollback rc) (catch Throwable _))
          (.close rc)
          (db-server/stop! server))))))

(deftest a-commit-that-never-arrives-does-not-leave-the-write-lock-held
  ;; Moving the commit out of the body's catch fixed a lie and introduced this:
  ;; a transport failure on /tx/commit left the transaction open on the far
  ;; side, holding SQLite's single write lock, until the idle sweep got to it a
  ;; minute later. Everyone else met SQLITE_BUSY in the meantime. The commit
  ;; goes through `rollback-after!` now, whose treatment of a 410 is right for
  ;; all three ways a commit can fail -- see its docstring.
  (with-remote
    (fn [remote server]
      (db/execute-one! remote ["CREATE TABLE t (n INTEGER)"])
      (let [real @#'db/post!
            thrown (with-redefs [db/post! (fn [handle path body]
                                            (if (= path "/tx/commit")
                                              (throw (java.io.IOException.
                                                       "connection reset by peer"))
                                              (real handle path body)))]
                     (try (db/with-transaction [tx remote]
                            (db/execute-one! tx ["INSERT INTO t (n) VALUES (1)"]))
                          nil
                          (catch Throwable t t)))]
        (is (instance? java.io.IOException thrown)
            "the transport failure is what the caller hears, unchanged")
        (is (empty? @(:transactions server))
            "and the transaction was rolled back at once rather than left for the sweeper")
        (testing "so the next writer is not locked out"
          (is (= #:next.jdbc{:update-count 1}
                 (db/execute-one! remote ["INSERT INTO t (n) VALUES (2)"]))))
        (is (= [2] (rows remote)) "and what the failed transaction wrote is not there")))))

(deftest a-transaction-swept-while-its-body-was-slow-says-so-plainly
  ;; What the timeout is for is a client that went away, and a body that spends
  ;; longer than the window between statements is indistinguishable from one.
  ;; So this transaction does lose its rows -- but the app has to hear the
  ;; truth about it, and not `Rollback failed handling …`, which is what it
  ;; heard before: the compensating rollback 410s on a token the sweeper has
  ;; already taken, and a 410 there means there is nothing left to roll back.
  (with-remote {:tx-idle-ms 200}
    (fn [remote server]
      (db/execute-one! remote ["CREATE TABLE t (n INTEGER)"])
      (let [thrown (try (db/with-transaction [tx remote]
                          (db/execute-one! tx ["INSERT INTO t (n) VALUES (1)"])
                          (is (wait-for 15000 #(empty? @(:transactions server)))
                              "the sweeper took it while the body was thinking")
                          (db/execute-one! tx ["INSERT INTO t (n) VALUES (2)"]))
                        nil
                        (catch Throwable t t))]
        (is (instance? clojure.lang.ExceptionInfo thrown))
        (is (= :db-server/unknown-transaction (:type (ex-data thrown)))
            "the true error: the transaction it was using is gone")
        (is (re-find #"idle" (.getMessage thrown)) "and it names why")
        (is (not (re-find #"Rollback failed" (.getMessage thrown)))
            "and does not assert a rollback failure that did not happen"))
      (is (= [] (rows remote)) "the sweep did roll it back"))))

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

(deftest the-health-answer-is-not-believed-forever
  ;; A db-server is a process and can be restarted on its own. An answer cached
  ;; for the life of the app goes out of step exactly then -- install the vec
  ;; extension, restart the inner server, and the app goes on saying it is not
  ;; there, with `clear-item-embedding!` quietly no longer deleting superseded
  ;; embeddings. Nothing fails; semantic search just starts matching text the
  ;; item no longer has.
  ;;
  ;; This cannot install a dylib mid-test, so what it pins is the mechanism: an
  ;; answer given by a db-server that is gone is not still being served after
  ;; the lifetime is up.
  (with-remote
    (fn [remote server]
      (is (boolean? (db/vec-available? remote)) "asked and answered")
      (db-server/stop! server)
      (testing "within the lifetime the remembered answer stands"
        (is (boolean? (db/vec-available? remote))))
      (testing "and once it is up, the question goes back to a db-server that is not there"
        (with-redefs [db/health-ttl-ms 0]
          (is (thrown? Exception (db/vec-available? remote))))))))

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
  ;;
  ;; Which is why it is read off the suite's own datasource rather than written
  ;; down here. A test about not letting the two names drift onto two databases
  ;; had no business restating the name of the one.
  (let [server (db-server/start!
                 {:port 0 :db-path (db-harness/dbname-of (:db config/config))})
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

(ns db-server.protocol-test
  "The db-server's own suite, against the protocol rather than through the
   client that speaks it.

   The requests here are built by hand -- transit in, transit out, over real
   HTTP -- deliberately: `db`'s remote handles are one client, and a protocol
   that only works when talked to by its own client is not a protocol. The
   facade's half is tested next door, in db-server.remote-handle-test.

   Every test boots a server of its own on an ephemeral port against a
   throwaway file database, so nothing here shares state with anything else in
   the suite."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cognitect.transit :as transit]
            [datastore.connection :as connection]
            [db-server]
            [next.jdbc :as jdbc])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

;; -- talking to it ---------------------------------------------------------

(def ^:private transit-type "application/transit+json")

(defn- write-transit ^bytes [v]
  (let [os (ByteArrayOutputStream. 1024)]
    (transit/write (transit/writer os :json) v)
    (.toByteArray os)))

(defn- read-transit [^bytes b]
  (transit/read (transit/reader (ByteArrayInputStream. b) :json)))

(defn- post!
  "POST a transit body to `path`, and answer `[status answer]`."
  [server path body]
  (let [resp (http/post (str (:url server) path)
                        {:body             (write-transit body)
                         :content-type     transit-type
                         :accept           transit-type
                         :as               :byte-array
                         :throw-exceptions false})]
    [(:status resp) (read-transit (:body resp))]))

(defn- get-json
  [server path]
  (let [resp (http/get (str (:url server) path) {:as :string :throw-exceptions false})]
    [(:status resp) (json/parse-string (:body resp) true)]))

(defn- result
  "The `:result` of a call that must have succeeded."
  [[status answer]]
  (is (= 200 status) (str "expected a 200, got " status ": " (pr-str answer)))
  (:result answer))

(defn- temp-db-path []
  (.getAbsolutePath (doto (java.io.File/createTempFile "rhizome-db-server-test" ".db")
                      (.deleteOnExit))))

(defn- with-server-at
  [db-path opts f]
  (let [server (db-server/start! (merge {:port 0 :db-path db-path} opts))]
    (try (f server) (finally (db-server/stop! server)))))

(defn- with-server
  ([f] (with-server {} f))
  ([opts f] (with-server-at (temp-db-path) opts f)))

(defn- with-table
  "A server with an empty `t (n INTEGER, s TEXT)` in it."
  [opts f]
  (with-server opts
    (fn [server]
      (result (post! server "/execute" {:stmt ["CREATE TABLE t (n INTEGER, s TEXT)"]}))
      (f server))))

(defn- wait-for
  "Poll `pred` until it answers truthy or `ms` runs out. Answers what it last
   saw, so a failed wait fails the assertion that reads it rather than here."
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [v (pred)]
        (if (or v (> (System/currentTimeMillis) deadline))
          v
          (do (Thread/sleep 50) (recur)))))))

;; -- statements ------------------------------------------------------------

(deftest statements-carry-their-parameters
  (with-table {}
    (fn [server]
      (result (post! server "/execute-one"
                     {:stmt ["INSERT INTO t (n, s) VALUES (?, ?)" 1 "it's quoted"]}))
      (testing "the parameter went through the driver, not through the SQL"
        (is (= [#:t{:n 1 :s "it's quoted"}]
               (result (post! server "/execute" {:stmt ["SELECT n, s FROM t"]})))))
      (testing "a write answers next.jdbc's update count"
        (is (= #:next.jdbc{:update-count 1}
               (result (post! server "/execute-one"
                              {:stmt ["UPDATE t SET s = ? WHERE n = ?" "x" 1]})))))
      (testing "and a row that is not there is nil, which is why :result is wrapped"
        (let [[status answer] (post! server "/execute-one" {:stmt ["SELECT n FROM t WHERE n = 99"]})]
          (is (= 200 status))
          (is (contains? answer :result) "the wrapper is present")
          (is (nil? (:result answer)) "and what it carries is nil, not nothing"))))))

(deftest the-builder-option-is-a-name-and-not-code
  (with-table {}
    (fn [server]
      (result (post! server "/execute-one" {:stmt ["INSERT INTO t (n) VALUES (7)"]}))
      (testing "the default qualifies the key with the table and keeps its case"
        (is (= #:t{:MiXeD 7}
               (result (post! server "/execute-one" {:stmt ["SELECT n AS MiXeD FROM t"]})))))
      (testing ":unqualified-lower, resolved on this side out of the same whitelist"
        (is (= {:mixed 7}
               (result (post! server "/execute-one"
                              {:stmt ["SELECT n AS MiXeD FROM t"]
                               :opts {:builder :unqualified-lower}}))))))))

(deftest return-keys-answers-with-the-generated-key
  (with-table {}
    (fn [server]
      (let [plain (result (post! server "/execute-one" {:stmt ["INSERT INTO t (n) VALUES (1)"]}))
            keyed (result (post! server "/execute-one" {:stmt ["INSERT INTO t (n) VALUES (2)"]
                                                        :opts {:return-keys true}}))]
        (is (= #:next.jdbc{:update-count 1} plain))
        (is (not= plain keyed) "the option has to make a difference on this side too")
        (is (= [2] (vals keyed)) "the rowid the insert generated")))))

(deftest an-option-off-the-whitelist-is-refused-over-the-wire
  (with-table {}
    (fn [server]
      (testing "an option next.jdbc understands and the protocol does not"
        (let [[status answer] (post! server "/execute" {:stmt ["SELECT 1"] :opts {:timeout 5}})]
          (is (= 400 status))
          (is (re-find #"unsupported statement option" (:error answer)))))
      (testing "and a builder name that is not on the list"
        (let [[status answer] (post! server "/execute"
                                     {:stmt ["SELECT 1"] :opts {:builder :qualified-kebab}})]
          (is (= 400 status))
          (is (re-find #"unknown result-set builder" (:error answer)))))
      (testing "a statement that is not [sql & params]"
        (is (= 400 (first (post! server "/execute" {:stmt "SELECT 1"}))))))))

(deftest a-statement-the-database-refuses-comes-back-marked-as-the-database-refusing
  (with-table {}
    (fn [server]
      (let [[status answer] (post! server "/execute" {:stmt ["SELECT * FROM nope"]})]
        (is (= 500 status))
        (is (true? (:sql? answer))
            "the marking is what lets the client rethrow it as the SQLException it would be locally")
        (is (re-find #"no such table" (:error answer)))))))

;; -- transactions ----------------------------------------------------------

(deftest a-transaction-commits-and-another-rolls-back
  (with-table {}
    (fn [server]
      (let [a (:tx (second (post! server "/tx/begin" {})))]
        (is (string? a))
        (result (post! server "/execute-one" {:stmt ["INSERT INTO t (n) VALUES (1)"] :tx a}))
        (testing "nothing is visible outside the transaction while it is open"
          (is (= [] (result (post! server "/execute" {:stmt ["SELECT n FROM t"]})))))
        (is (= {:ok true} (second (post! server "/tx/commit" {:tx a}))))
        (is (= [#:t{:n 1}] (result (post! server "/execute" {:stmt ["SELECT n FROM t"]})))))
      (let [b (:tx (second (post! server "/tx/begin" {})))]
        (result (post! server "/execute-one" {:stmt ["INSERT INTO t (n) VALUES (2)"] :tx b}))
        (is (= {:ok true} (second (post! server "/tx/rollback" {:tx b}))))
        (is (= [#:t{:n 1}] (result (post! server "/execute" {:stmt ["SELECT n FROM t"]})))
            "the rolled-back row is gone and the committed one is not")))))

(deftest begin-refuses-a-request-that-already-carries-a-transaction
  (with-table {}
    (fn [server]
      (let [a (:tx (second (post! server "/tx/begin" {})))
            [status answer] (post! server "/tx/begin" {:tx a})]
        (is (= 409 status))
        (is (= :db-server/nested-transaction (:type answer)))
        (is (re-find #"already a transaction" (:error answer))
            "the same rule the facade enforces locally, stated the same way")
        (post! server "/tx/rollback" {:tx a})))))

(deftest a-token-that-names-no-transaction-is-refused-clearly
  (with-table {}
    (fn [server]
      (doseq [[path body] [["/execute" {:stmt ["SELECT 1"] :tx "not-a-token"}]
                           ["/execute-one" {:stmt ["SELECT 1"] :tx "not-a-token"}]
                           ["/tx/commit" {:tx "not-a-token"}]
                           ["/tx/rollback" {:tx "not-a-token"}]]]
        (let [[status answer] (post! server path body)]
          (is (= 410 status) (str path " should answer 410"))
          (is (= :db-server/unknown-transaction (:type answer)) (str path))))
      (testing "and a token that has been used up is in exactly that state"
        (let [a (:tx (second (post! server "/tx/begin" {})))]
          (post! server "/tx/commit" {:tx a})
          (is (= 410 (first (post! server "/execute" {:stmt ["SELECT 1"] :tx a})))))))))

(deftest a-transaction-nobody-came-back-for-is-rolled-back-and-freed
  (with-table {:tx-idle-ms 300}
    (fn [server]
      (let [a (:tx (second (post! server "/tx/begin" {})))]
        (result (post! server "/execute-one" {:stmt ["INSERT INTO t (n) VALUES (1)"] :tx a}))
        ;; Watched through the registry rather than by asking the server about
        ;; the token: a statement on a transaction resets its idle clock, which
        ;; is what "untouched" means, so a poll that went over the wire would
        ;; keep the thing it is waiting for alive. Nothing here calls the sweep
        ;; -- what this waits on is the timer inside `start!`.
        (let [gone? (wait-for 15000 #(empty? @(:transactions server)))]
          (is gone? "the sweeper took the abandoned transaction away"))
        (is (= 410 (first (post! server "/execute" {:stmt ["SELECT 1"] :tx a})))
            "and its token now names nothing")
        (is (= [] (result (post! server "/execute" {:stmt ["SELECT n FROM t"]})))
            "and rolled it back on the way, so nothing half-written survived it")
        (is (= 410 (first (post! server "/tx/commit" {:tx a})))
            "committing it afterwards is refused rather than quietly doing nothing")))))

(deftest the-idle-clock-restarts-with-every-statement
  ;; The window is on the gap between statements, not on the transaction's
  ;; whole life, and this is what says so. Take the touches out of `acquire!`
  ;; and `release!` and the registry's clock never moves after `/tx/begin` --
  ;; at which point a transaction doing perfectly ordinary work dies as soon as
  ;; it has been alive for the window, which is every transaction of any size.
  ;; Nothing else in this suite notices that, because nothing else keeps a
  ;; transaction open across more than one gap.
  (with-table {:tx-idle-ms 400}
    (fn [server]
      (testing "a transaction that lives far longer than the window, in short steps"
        (let [a (:tx (second (post! server "/tx/begin" {})))]
          (dotimes [n 6]
            (Thread/sleep 150)                       ; each gap well inside the window
            (let [[status answer] (post! server "/execute-one"
                                         {:stmt ["INSERT INTO t (n) VALUES (?)" n] :tx a})]
              (is (= 200 status) (str "statement " n " after " (* 150 (inc n)) "ms: "
                                      (pr-str answer)))))
          (is (= {:ok true} (second (post! server "/tx/commit" {:tx a})))
              "and it is still there to commit, 900ms into a 400ms window")
          (is (= 6 (count (result (post! server "/execute" {:stmt ["SELECT n FROM t"]}))))))))
    ))

(def ^:private counting-cte
  "A statement whose only property of interest is that it takes a while: count
   to `n` in SQLite and report how far it got."
  (fn [n]
    (str "WITH RECURSIVE c(x) AS "
         "(SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x < " n ") "
         "SELECT count(*) AS n FROM c")))

(defn- rows-that-outlast
  "How far this machine has to count for the statement to take longer than
   `ms`, measured here rather than written down.

   It was written down once -- four million, which took just over 400ms on the
   machine of the day -- and by the next box it was 320ms, so the test that
   depends on the statement outlasting the window started failing on its own
   premise. Which is what that premise is asserted for; but a number that has
   to be re-tuned per machine will rot again on the next one, and the loop that
   finds it costs a second at most.

   Doubling, from the old constant, with a ceiling so a pathological machine
   fails the test rather than running forever."
  [server ms]
  (loop [n 4000000]
    (let [began (System/currentTimeMillis)
          _     (result (post! server "/execute-one" {:stmt [(counting-cte n)]}))
          took  (- (System/currentTimeMillis) began)]
      (cond (> took ms)     n
            (>= n 512000000) n
            :else           (recur (* 2 n))))))

(deftest the-clock-restarts-when-a-statement-ENDS-not-when-it-starts
  ;; The other half of the same rule, and the one that catches `release!`
  ;; specifically: after a statement that itself outlasted the window, the
  ;; transaction has to get a full window of quiet before it is reaped. If the
  ;; clock were only set on the way IN, it would already be past the cutoff the
  ;; moment the statement returned, and the very next gap would lose it.
  (with-table {:tx-idle-ms 400}
    (fn [server]
      (let [n     (rows-that-outlast server 400)
            a     (:tx (second (post! server "/tx/begin" {})))
            began (System/currentTimeMillis)
            slow  (post! server "/execute-one" {:stmt [(counting-cte n)] :tx a})
            took  (- (System/currentTimeMillis) began)]
        (is (= 200 (first slow)))
        ;; The premise, asserted rather than assumed. A statement that came in
        ;; under the window would leave this test passing while testing nothing
        ;; at all -- the gap after a SHORT statement is not the case it exists
        ;; for. `rows-that-outlast` calibrated for exactly this a moment ago, so
        ;; this firing now means the machine got faster between two statements.
        (is (> took 400)
            (str "the statement has to outlast the idle window to mean anything; took "
                 took "ms at " n " rows"))
        (Thread/sleep 150)
        (is (= 200 (first (post! server "/execute-one"
                                 {:stmt ["INSERT INTO t (n) VALUES (1)"] :tx a})))
            "and a short gap after it is a short gap, not an expired transaction")
        (post! server "/tx/commit" {:tx a})))))

(deftest a-commit-while-a-statement-is-running-is-refused-rather-than-closing-under-it
  ;; Not reachable through the facade, which is single-threaded per transaction
  ;; -- but the protocol is open to anything that can speak it, and closing a
  ;; connection with a statement on it is the same "stmt pointer is closed" the
  ;; sweeper used to cause. The in-flight count is read inside the swap that
  ;; takes the entry out, so the refusal is a decision and not a guess.
  (with-table {}
    (fn [server]
      (let [a       (:tx (second (post! server "/tx/begin" {})))
            slow    (future (post! server "/execute-one"
                                   {:stmt [(str "WITH RECURSIVE c(x) AS "
                                                "(SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x < 8000000) "
                                                "SELECT count(*) AS n FROM c")]
                                    :tx   a}))
            running (wait-for 10000 #(pos? (:in-flight (get @(:transactions server) a) 0)))]
        (is running "the slow statement is on the connection")
        (let [[status answer] (post! server "/tx/commit" {:tx a})]
          (is (= 409 status))
          (is (= :db-server/transaction-busy (:type answer)))
          (is (re-find #"let it finish" (:error answer))))
        (is (= 200 (first @slow)) "and the statement it would have cut off ran to the end")
        (is (= {:ok true} (second (post! server "/tx/commit" {:tx a})))
            "committing once it is quiet works, as it always did")))))

(deftest two-write-transactions-do-not-interleave
  ;; SQLite's law, inherited rather than reimplemented: the datasource begins
  ;; IMMEDIATE, so the write lock is taken as a transaction opens and the second
  ;; one waits out the driver's 3s busy_timeout and is then refused. There is no
  ;; queue in front of that here -- this test pins the absence of one.
  (with-table {}
    (fn [server]
      (let [a (:tx (second (post! server "/tx/begin" {})))]
        (result (post! server "/execute-one" {:stmt ["INSERT INTO t (n) VALUES (1)"] :tx a}))
        (let [[status answer] (post! server "/tx/begin" {})]
          (is (= 500 status) "the second transaction cannot open while the first holds the lock")
          (is (true? (:sql? answer)))
          (is (str/includes? (:error answer) "SQLITE_BUSY")))
        (post! server "/tx/commit" {:tx a})
        (testing "and once the first is done, the next one opens"
          (let [b (:tx (second (post! server "/tx/begin" {})))]
            (is (string? b))
            (result (post! server "/execute-one" {:stmt ["INSERT INTO t (n) VALUES (2)"] :tx b}))
            (post! server "/tx/commit" {:tx b})
            (is (= [#:t{:n 1} #:t{:n 2}]
                   (result (post! server "/execute" {:stmt ["SELECT n FROM t ORDER BY n"]}))))))))))

;; -- read-only -------------------------------------------------------------

(deftest the-sweeper-never-takes-a-transaction-that-is-still-working
  ;; The idle clock says when a transaction last went quiet, and a statement
  ;; that is still running has not. Without an in-flight count the sweeper
  ;; closes the connection under it, and what comes back is
  ;; "stmt pointer is closed" -- an error about nothing the caller did, on a
  ;; transaction that was alive the whole time.
  (with-table {:tx-idle-ms 200}
    (fn [server]
      (let [a (:tx (second (post! server "/tx/begin" {})))]
        (result (post! server "/execute-one" {:stmt ["INSERT INTO t (n) VALUES (1)"] :tx a}))
        (testing "a statement that outlives the idle window several times over"
          (let [[status answer]
                (post! server "/execute-one"
                       {:stmt [(str "WITH RECURSIVE c(x) AS "
                                    "(SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x < 4000000) "
                                    "SELECT count(*) AS n FROM c")]
                        :tx   a})]
            (is (= 200 status) (str "the statement ran to the end: " (pr-str answer)))))
        (testing "and the transaction it was running in is still there"
          (is (= 200 (first (post! server "/execute" {:stmt ["SELECT n FROM t"] :tx a}))))
          (is (= {:ok true} (second (post! server "/tx/commit" {:tx a})))))
        (is (= [#:t{:n 1}] (result (post! server "/execute" {:stmt ["SELECT n FROM t"]})))
            "and committed what it wrote")))))

(deftest a-commit-the-database-refuses-comes-back-as-the-database-refusing-it
  ;; The db-server rolls back and closes behind a commit that throws, and the
  ;; commit's own exception is what comes out -- next.jdbc's local outcome. The
  ;; token is freed either way, so a client's compensating rollback finds
  ;; nothing; that it must not then report a rollback failure is pinned next
  ;; door, in db-server.remote-handle-test.
  (let [path (temp-db-path)]
    (with-server-at path {}
      (fn [server]
        (result (post! server "/execute" {:stmt ["CREATE TABLE t (n INTEGER)"]}))))
    (with-server-at path {}
      (fn [server]
        ;; A DEFERRED reader holding SHARED: the writer may begin and insert,
        ;; and is refused when its COMMIT reaches for EXCLUSIVE.
        (let [reader (connection/make-datasource {:dbname path :read-only? true})
              rc     (.getConnection reader)]
          (try
            (.setAutoCommit rc false)
            (jdbc/execute! rc ["SELECT n FROM t"])
            (let [a (:tx (second (post! server "/tx/begin" {})))]
              (result (post! server "/execute-one" {:stmt ["INSERT INTO t (n) VALUES (1)"] :tx a}))
              (let [[status answer] (post! server "/tx/commit" {:tx a})]
                (is (= 500 status))
                (is (true? (:sql? answer)) "it is the database refusing, not the protocol")
                (is (str/includes? (:error answer) "SQLITE_BUSY")))
              (is (= 410 (first (post! server "/execute" {:stmt ["SELECT 1"] :tx a})))
                  "and the token is freed, because the rollback behind it already ran"))
            (finally (try (.rollback rc) (catch Throwable _)) (.close rc))))))))

(deftest a-sql-failure-carries-the-state-and-code-the-driver-gave-it
  (with-table {}
    (fn [server]
      (let [[_ answer] (post! server "/execute" {:stmt ["SELECT * FROM nope"]})]
        (is (true? (:sql? answer)))
        (is (contains? answer :sql-state) "so the facade can rebuild what the driver raised")
        (is (integer? (:error-code answer)))
        (is (= (:error-code answer)
               (-> (try (jdbc/execute! (connection/make-datasource {:dbname (temp-db-path)})
                                       ["SELECT * FROM nope"])
                        (catch java.sql.SQLException e e))
                   .getErrorCode))
            "the same code the local driver reports for the same statement")))))

(deftest a-read-only-server-reads-and-refuses-to-write
  (let [path (temp-db-path)]
    (with-server-at path {} (fn [server]
                              (result (post! server "/execute" {:stmt ["CREATE TABLE t (n INTEGER)"]}))
                              (result (post! server "/execute-one" {:stmt ["INSERT INTO t (n) VALUES (1)"]}))))
    (with-server-at path {:read-only? true}
      (fn [server]
        (testing "it says so"
          (is (true? (:read-only? (second (get-json server "/health"))))))
        (testing "reads work"
          (is (= [#:t{:n 1}] (result (post! server "/execute" {:stmt ["SELECT n FROM t"]})))))
        (testing "and a write is refused by the driver, not by a rule up here"
          (let [[status answer] (post! server "/execute-one" {:stmt ["INSERT INTO t (n) VALUES (2)"]})]
            (is (= 500 status))
            (is (true? (:sql? answer)))
            (is (re-find #"(?i)readonly|read-only" (:error answer)))))))))

;; -- the JSON surface ------------------------------------------------------

(deftest a-server-cannot-boot-against-a-database-it-cannot-open
  ;; A read-only datasource never creates or touches the file, and SQLite opens
  ;; lazily, so before this the server came up perfectly against a path that was
  ;; not there and failed on its first statement. Step 4's start script waits on
  ;; /health and then starts the app-server in front of it, so a green boot has
  ;; to mean the database is actually reachable.
  (let [missing (str (System/getProperty "java.io.tmpdir")
                     "/rhizome-db-server-test-absent-" (rand-int 1000000) ".db")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot reach the database"
                          (db-server/start! {:port 0 :db-path missing :read-only? true})))))

(deftest health-refuses-to-say-ok-for-a-database-it-cannot-reach
  ;; Driven at the handler rather than over HTTP, because `start!` now refuses
  ;; to come up against an unreachable database at all -- there is no way left
  ;; to get a running server into this state, which is the point. What is pinned
  ;; here is that /health asks the database instead of reporting the process's
  ;; own opinion of itself, so a start script cannot be told `ok` about a
  ;; database that will refuse its first statement.
  (let [missing (str (System/getProperty "java.io.tmpdir")
                     "/rhizome-db-server-test-absent-" (rand-int 1000000) ".db")
        unreachable {:ds         (connection/make-datasource {:dbname missing :read-only? true})
                     :read-only? true}
        {:keys [status body]} (db-server/health unreachable)]
    (is (= 503 status))
    (let [answer (json/parse-string body true)]
      (is (false? (:ok answer)))
      (is (re-find #"(?i)unable to open" (:error answer))
          "and it says what the database said"))))

(deftest a-vec-path-this-process-cannot-honour-is-refused
  ;; The one option `start!` takes and does not act on. `datastore.connection`
  ;; resolves the extension path once, at load, out of `:db-server :vec-path`
  ;; in config.edn -- the same key `config-opts` reads, so a server booted from
  ;; the file agrees with it and this never fires there. What it is for is a
  ;; caller passing a *different* path: the extension is loaded on every
  ;; connection the datasource hands out, and no argument here could change
  ;; that after the fact. Checked rather than accepted-and-ignored, and this is
  ;; the assertion that says so.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"could not take effect"
                        (db-server/start! {:port 0
                                           :db-path (temp-db-path)
                                           :vec-path "/nowhere/in/particular/vec0"})))
  (testing "and the path that WAS loaded is accepted, which is what `-main` passes"
    (with-server-at (temp-db-path) {:vec-path connection/vec-extension-path}
      (fn [server] (is (= 200 (first (get-json server "/health"))))))))

(deftest a-host-that-is-not-loopback-is-refused-rather-than-ignored
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":host is not an option"
                        (db-server/start! {:port 0 :db-path (temp-db-path) :host "0.0.0.0"}))))

(deftest a-body-that-is-not-transit-is-a-refusal-and-not-a-crash
  (with-server {}
    (fn [server]
      (doseq [[label body] [["nonsense bytes" (.getBytes "{not transit at all" "UTF-8")]
                            ["nothing at all" (byte-array 0)]]]
        (testing label
          (let [resp (http/post (str (:url server) "/execute")
                                {:body             body
                                 :content-type     transit-type
                                 :as               :byte-array
                                 :throw-exceptions false})]
            (is (= 400 (:status resp)))
            (is (= :db-server/bad-request (:type (read-transit (:body resp)))))))))))

(deftest stopping-rolls-back-the-transactions-that-were-still-open
  ;; A held connection holds the write lock. In a process that is exiting that
  ;; is invisible; in step 3's harness, where a db-server is started and stopped
  ;; inside the JVM the next test runs in, it is a database nobody can write to.
  (let [path   (temp-db-path)
        server (db-server/start! {:port 0 :db-path path})]
    (result (post! server "/execute" {:stmt ["CREATE TABLE t (n INTEGER)"]}))
    (let [a (:tx (second (post! server "/tx/begin" {})))]
      (result (post! server "/execute-one" {:stmt ["INSERT INTO t (n) VALUES (1)"] :tx a}))
      (is (= 1 (count @(:transactions server))) "one transaction open, holding the write lock")
      (db-server/stop! server)
      (is (empty? @(:transactions server)) "and stop! did not leave it there"))
    (testing "it was rolled back, and the database is writable again"
      (with-server-at path {}
        (fn [server]
          (is (= [] (result (post! server "/execute" {:stmt ["SELECT n FROM t"]}))))
          (is (= 200 (first (post! server "/execute-one"
                                   {:stmt ["INSERT INTO t (n) VALUES (2)"]})))))))))

(deftest health-says-what-it-is
  (with-server {}
    (fn [server]
      (let [[status body] (get-json server "/health")]
        (is (= 200 status))
        (is (= true (:ok body)))
        (is (false? (:read-only? body)))
        (is (contains? body :vec-available?))
        (is (boolean? (:vec-available? body)))))))

(deftest describe-answers-the-family-shape
  (with-server {}
    (fn [server]
      (let [[status body] (get-json server "/api/describe")]
        (is (= 200 status))
        (is (= #{:endpoints :skill} (set (keys body))) "the shape the family answers")
        (is (string? (:skill body)))
        (is (str/includes? (:skill body) "transit"))
        (testing "every route is listed, named, and documented"
          (is (= ["describe" "execute" "execute-one" "health" "tx-begin" "tx-commit" "tx-rollback"]
                 (mapv :name (:endpoints body))))
          (is (every? (comp seq :doc) (:endpoints body))))
        (testing "and each doc leads with the method and path it documents"
          (is (every? #(re-find #"\A(GET|POST) /\S+ " (:doc %)) (:endpoints body))))))))

(deftest a-route-that-is-not-there-says-so
  (with-server {}
    (fn [server]
      (let [[status body] (get-json server "/nope")]
        (is (= 404 status))
        (is (re-find #"no such route" (:error body)))))))

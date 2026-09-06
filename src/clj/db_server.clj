(ns db-server
  "The inner server: the one process that opens rhizome's SQLite file.

   It speaks statements and knows nothing else. There is no endpoint here that
   mentions an item, a context or a relation, and there is not going to be one
   -- everything that knows what the rows *mean* is on the other side of this
   wire, in the app-server. What this owns is the file, the sqlite-vec
   extension, the schema, and the connections a transaction is held on.

   The protocol it answers is the one `db`'s remote handles speak, and the two
   share their definition rather than agreeing to match: the option whitelist
   arriving over the wire is resolved by `db/jdbc-opts`, the same function the
   local branch of the facade calls. One list, read by both ends.

   Bodies are transit, except `/health` and `/api/describe`, which answer JSON
   -- those two are read by start scripts and by prober, and neither should
   have to speak the statement protocol to ask whether the database is up.

   Boot with `start!` and an opts map shaped like the `:db-server` config
   section; `stop!` takes what it returns. **`start!` itself still reads no
   configuration** -- that is what lets a test boot as many of these as it
   likes on ephemeral ports -- so the file is read by `config-opts` and the
   `-main` below it, and nowhere else."
  (:require log-init ;; first: sets LOGS_DIR before any logging ns initialises logback
            [aero.core :as aero]
            [cambium.core :as log]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [compojure.core :refer [GET POST routes]]
            [datastore.connection :as connection]
            [datastore.schema :as schema]
            [db :as db]
            [next.jdbc :as jdbc]
            [ring.adapter.jetty :as jetty]
            [role :as role])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.sql Connection SQLException]
           [java.util.concurrent Executors TimeUnit]
           [javax.sql DataSource]
           [org.eclipse.jetty.server ServerConnector]))

(def ^:private loopback
  "The only address this ever binds. Not an option: the routes below run
   arbitrary SQL with no authentication of any kind, so a `:host` argument
   would be a one-word way to publish the database to the network. The plan
   puts LAN exposure and the auth that has to come with it in a later step
   than this one."
  "127.0.0.1")

(def default-tx-idle-ms
  "How long a transaction may go untouched before it is rolled back and its
   connection freed. A held transaction holds SQLite's write lock, so an
   abandoned one -- a client that crashed between `/tx/begin` and `/tx/commit`
   -- would otherwise lock the database against every writer until this process
   restarts."
  60000)

;; -- wire ------------------------------------------------------------------

(def ^:private transit-type "application/transit+json")

(defn- transit-body
  "The transit map in a request. An absent or unreadable body is a refusal
   rather than an empty map: every route here takes arguments."
  [req]
  (try (transit/read (transit/reader (:body req) :json))
       (catch Throwable t
         (throw (ex-info (str "db-server: could not read the transit request body: "
                              (.getMessage t))
                         {:type :db-server/bad-request})))))

(defn- transit-response
  [status body]
  {:status  status
   :headers {"Content-Type" transit-type}
   :body    (let [os (ByteArrayOutputStream. 1024)]
              (transit/write (transit/writer os :json) body)
              (ByteArrayInputStream. (.toByteArray os)))})

(defn- json-response
  [status body]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (json/generate-string body)})

;; -- transactions ----------------------------------------------------------
;;
;; One held Connection per token. SQLite's concurrency is inherited rather than
;; reimplemented: the datasource begins IMMEDIATE, so a write transaction takes
;; the write lock as it opens, and a second one waits out the driver's 3s
;; busy_timeout and then fails with SQLITE_BUSY. There is deliberately no queue
;; in front of that -- one writer at a time is the database's own law, and a
;; queue here would only be a second, disagreeing opinion about it.

(defn- new-token [] (str (java.util.UUID/randomUUID)))

(defn- begin-transaction!
  [{:keys [^DataSource ds transactions]}]
  (let [token (new-token)
        conn  (.getConnection ds)]
    (try
      (.setAutoCommit ^Connection conn false)
      (swap! transactions assoc token {:conn       conn
                                       :touched-ms (System/currentTimeMillis)
                                       :in-flight  0})
      token
      (catch Throwable t
        ;; Nothing was registered, and after a BEGIN that failed there is no
        ;; transaction on this connection to roll back -- SQLite says so in as
        ;; many words if you try. Close it and let the failure out.
        (try (.close ^Connection conn) (catch Throwable _))
        (throw t)))))

(defn- finish!
  "Commit or roll back `conn` and close it, whatever happens.

   A commit the database refuses is rolled back before the connection goes, and
   the commit's own exception is what comes out -- next.jdbc does the same
   locally, and the point of this seam is that the two are indistinguishable."
  [^Connection conn commit?]
  (try
    (if commit?
      (try (.commit conn)
           (catch Throwable t
             (try (.rollback conn) (catch Throwable _))
             (throw t)))
      (.rollback conn))
    (finally (try (.close conn) (catch Throwable _)))))

(defn- close-transaction!
  "Commit or roll back the transaction `token` names and free its connection.
   Answers `:closed`, `:busy` -- a statement is running on it -- or `:unknown`.

   Taking the entry out and deciding whether it may be taken are one `swap!`,
   which buys two distinct things. No two callers can both close the same
   connection: exactly one of them finds the entry in `before` and the others
   get `:unknown`. And nothing closes a connection with a statement still on
   it: `:in-flight` is read inside the swap, so a statement that arrives while
   this is deciding either raises the count first -- and this answers `:busy` --
   or arrives to find the token gone.

   `force?` skips the in-flight check, for `stop!`: at shutdown a held
   connection has to go whatever it is doing, because the alternative is
   leaking it with the write lock in its hand.

   That the token is freed even when the commit throws is deliberate: `finish!`
   has rolled back and closed by then, so there is nothing left to do a second
   time."
  ([server token commit?] (close-transaction! server token commit? false))
  ([{:keys [transactions]} token commit? force?]
   (let [[before after] (swap-vals! transactions
                                    (fn [m]
                                      (let [entry (get m token)]
                                        (if (and entry
                                                 (or force? (zero? (:in-flight entry))))
                                          (dissoc m token)
                                          m))))]
     (cond
       (not (contains? before token)) :unknown
       (contains? after token)        :busy
       :else (do (finish! (:conn (get before token)) commit?) :closed)))))

(defn- acquire!
  "The connection `token` names, with its idle clock reset and its in-flight
   count raised. Nil when the token names nothing.

   Raising the count is what keeps the sweeper off a transaction that is in the
   middle of a statement. Resetting the clock on the way in is not enough on its
   own: a statement can outlive the idle window all by itself, and the sweeper
   would then close the connection out from under it -- which surfaces as
   `stmt pointer is closed`, an error about nothing the caller did."
  [{:keys [transactions]} token]
  (let [m (swap! transactions
                 (fn [m]
                   (if (contains? m token)
                     (-> m
                         (assoc-in [token :touched-ms] (System/currentTimeMillis))
                         (update-in [token :in-flight] inc))
                     m)))]
    (:conn (get m token))))

(defn- release!
  "Give the transaction back after a statement, its idle clock starting from
   now -- from when the statement ENDED, which is when it actually went quiet."
  [{:keys [transactions]} token]
  (swap! transactions
         (fn [m]
           (if (contains? m token)
             (-> m
                 (update-in [token :in-flight] dec)
                 (assoc-in [token :touched-ms] (System/currentTimeMillis)))
             m)))
  nil)

(defn sweep-idle-transactions!
  "Roll back and free every transaction that has gone quiet for longer than the
   idle timeout, and answer how many there were.

   A transaction with a statement in flight is never taken, however long it has
   been running: the selection and the removal are one `swap!`, so a statement
   that arrives while the sweep is deciding either raises the count before the
   swap lands -- and the transaction is left alone -- or arrives after it and
   finds the token gone, which is a clean refusal rather than a closed
   connection under a running query.

   What this cannot protect is a transaction whose CLIENT has gone quiet: a body
   that spends longer than the window between statements is indistinguishable
   from one that died, and is rolled back. That is the timeout doing its job, and
   the app is told the truth about it -- see `db/transact`'s remote half.

   Run on a timer by `start!`; public so an operator at a REPL can free a stuck
   transaction without waiting the window out. The tests do not call it -- what
   they watch is the timer."
  [{:keys [transactions tx-idle-ms]}]
  (let [cutoff  (- (System/currentTimeMillis) tx-idle-ms)
        idle?   (fn [[_ {:keys [touched-ms in-flight]}]]
                  (and (zero? in-flight) (< touched-ms cutoff)))
        [before after] (swap-vals! transactions #(into {} (remove idle?) %))
        taken   (apply dissoc before (keys after))]
    (doseq [[token {:keys [conn]}] taken]
      (log/warn {:tx token :idle-ms tx-idle-ms}
                "db-server: rolling back a transaction nobody came back for")
      (try (finish! conn false)
           (catch Throwable t
             (log/error t "db-server: could not roll back an idle transaction"))))
    (count taken)))

(defn- close-or-refuse!
  "Close the transaction, or say why not."
  [server token commit?]
  (case (close-transaction! server token commit?)
    :closed  true
    :busy    (throw (ex-info (str "db-server: transaction " token
                                  " has a statement running on it -- let it finish first")
                             {:db-server/status 409 :type :db-server/transaction-busy}))
    :unknown (throw (ex-info (str "db-server: no such transaction: " token
                                  " -- it was committed, rolled back, or rolled back for being idle")
                             {:db-server/status 410 :type :db-server/unknown-transaction}))))

(defn- unknown-transaction!
  [token]
  (throw (ex-info (str "db-server: no such transaction: " token
                       " -- it was committed, rolled back, or rolled back for being idle")
                  {:db-server/status 410 :type :db-server/unknown-transaction})))

;; -- routes ----------------------------------------------------------------
;;
;; Every route is a public var carrying a docstring that leads with its method
;; and path, and marked ^:endpoint -- that is what `describe` reads. The marker
;; is positive here where rest-api's is negative (^:no-describe), because most
;; of what this namespace makes public is machinery rather than surface.

(defn- check-stmt!
  [stmt]
  (when-not (and (sequential? stmt) (string? (first stmt)))
    (throw (ex-info (str "db-server: :stmt must be [sql & params] with the sql a string, got "
                         (pr-str stmt))
                    {:type :db-server/bad-request})))
  (vec stmt))

(defn- run
  [target one? statement options]
  (if one?
    (jdbc/execute-one! target statement options)
    (jdbc/execute! target statement options)))

(defn- run-statement
  "Run one statement, on a transaction's held connection when a token names one
   and on a connection of its own otherwise.

   The token is acquired and released around the statement rather than merely
   touched before it, so the sweeper cannot close the connection while the
   statement is still on it."
  [server one? req]
  (let [{:keys [stmt opts tx]} (transit-body req)
        statement (check-stmt! stmt)
        options   (db/jdbc-opts (or opts {}))]
    (transit-response 200
      {:result (if tx
                 (if-let [conn (acquire! server tx)]
                   (try (run conn one? statement options)
                        (finally (release! server tx)))
                   (unknown-transaction! tx))
                 (run (:ds server) one? statement options))})))

(defn ^:endpoint execute
  "POST /execute — run a statement and answer every row it returns.

  Body `{:stmt [sql & params] :opts {…} :tx \"token\"?}`, answer
  `{:result [row …]}`. The result is wrapped so that a statement returning
  nothing is distinguishable from a response that carried nothing.

  `:opts` takes `:builder` and `:return-keys`, and refuses anything else rather
  than ignoring it -- an option this seam cannot carry has to be a refusal here,
  or it would be a statement quietly running differently than the caller asked.

  With `:tx`, the statement runs on the connection that token is holding;
  without it, on a connection of its own."
  [server req]
  (run-statement server false req))

(defn ^:endpoint execute-one
  "POST /execute-one — run a statement and answer its first row.

  Body and options exactly as POST /execute; the answer is `{:result row}`,
  where `row` is nil when the statement matched nothing and next.jdbc's
  `{:next.jdbc/update-count n}` when it was a write."
  [server req]
  (run-statement server true req))

(defn ^:endpoint tx-begin
  "POST /tx/begin — open a transaction and answer `{:tx \"token\"}`.

  The token names a connection held open for you. Pass it as `:tx` on the
  statements that belong to the transaction, and finish with POST /tx/commit or
  POST /tx/rollback. A transaction left untouched for 60 seconds is rolled back
  and its token freed.

  Body `{}`. A body carrying a `:tx` is refused: a handle that is already a
  transaction may not be made one again, which is the same rule the app-side
  facade enforces on itself before it ever gets here."
  [server req]
  (let [{:keys [tx]} (transit-body req)]
    (when tx
      (throw (ex-info (str "db-server: /tx/begin takes no transaction -- "
                           "a handle that is already a transaction may not be made one again")
                      {:db-server/status 409 :type :db-server/nested-transaction})))
    (transit-response 200 {:tx (begin-transaction! server)})))

(defn ^:endpoint tx-commit
  "POST /tx/commit — commit the transaction a token names and free it.

  Body `{:tx \"token\"}`, answer `{:ok true}`. A token that names no open
  transaction answers 410, which is also what a transaction that was swept for
  idleness answers: in both cases nothing of it survives."
  [server req]
  (let [{:keys [tx]} (transit-body req)]
    (when-not tx
      (throw (ex-info "db-server: /tx/commit needs a :tx token"
                      {:type :db-server/bad-request})))
    (close-or-refuse! server tx true)
    (transit-response 200 {:ok true})))

(defn ^:endpoint tx-rollback
  "POST /tx/rollback — roll the transaction a token names back and free it.

  Body `{:tx \"token\"}`, answer `{:ok true}`. 410 for a token that names no
  open transaction, as POST /tx/commit."
  [server req]
  (let [{:keys [tx]} (transit-body req)]
    (when-not tx
      (throw (ex-info "db-server: /tx/rollback needs a :tx token"
                      {:type :db-server/bad-request})))
    (close-or-refuse! server tx false)
    (transit-response 200 {:ok true})))

(defn- reach-the-database!
  "Run the smallest possible statement, to find out whether the database is
   actually there. Building a datasource proves nothing: SQLite opens lazily,
   and a read-only datasource in particular never creates or touches the file,
   so a db-server pointed at a path that does not exist comes up perfectly and
   fails on its first real statement."
  [{:keys [ds]}]
  (jdbc/execute-one! ds ["SELECT 1"]))

(defn ^:endpoint health
  "GET /health — `{:ok true :read-only? b :vec-available? b}`, as JSON.

  What a start script waits on and what a prober reads, so it answers plain
  JSON rather than transit and says everything either of them needs in one
  line. `:read-only?` is whether this process opened the database read-only --
  a replica, where every write fails at the driver. `:vec-available?` is
  whether the sqlite-vec extension loaded, which is what decides whether the
  items_vec statements can run at all.

  It asks the database rather than reporting this process's own opinion of
  itself. A health check that says `ok` while the first statement will fail with
  SQLITE_CANTOPEN is worse than none, because what waits on it starts the thing
  in front. 503 and the reason, when the database cannot be reached."
  [server]
  (try
    (reach-the-database! server)
    (json-response 200 {:ok             true
                        :read-only?     (boolean (:read-only? server))
                        :vec-available? connection/vec-available?})
    (catch Throwable t
      (log/error t "db-server: /health could not reach the database")
      (json-response 503 {:ok             false
                          :error          (str (.getMessage t))
                          :read-only?     (boolean (:read-only? server))
                          :vec-available? connection/vec-available?}))))

(def ^:private skill-resource "rhizome-db/SKILL.md")

(def ^:private skill-md
  (delay (when-let [r (io/resource skill-resource)] (str/trim (slurp r)))))

(defn ^:endpoint describe
  "GET /api/describe — what this server is and every route it answers, as JSON.

  `{:endpoints [{:name :doc} …] :skill \"…\"}`, the shape rhizome's own
  /api/describe answers and the one the other plurama apps answer, so anything
  that reads one of them can read this. `:endpoints` is built from the routes
  themselves rather than maintained beside them; `:skill` is
  resources/rhizome-db/SKILL.md, which teaches the protocol.

  This route lists itself, which rhizome's does not. A seven-route protocol
  that a prober discovers in one call is better served by a complete list than
  by the convention of leaving the describing route out of it."
  []
  (json-response 200
    {:endpoints (->> (ns-publics 'db-server)
                     (keep (fn [[sym v]]
                             (when (and (:endpoint (meta v)) (:doc (meta v)))
                               {:name (str sym) :doc (:doc (meta v))})))
                     (sort-by :name)
                     vec)
     :skill     @skill-md}))

(defn- wrap-refusals
  "Turn what a route throws into the answer the client can read.

   A statement the *database* refused is marked `:sql?` and keeps its message,
   because the facade rethrows those as `SQLException` -- the type a local
   handle raises for the same statement. The protocol's own refusals carry the
   status they chose in their ex-data, defaulting to 400."
  [handler]
  (fn [req]
    (try
      (handler req)
      (catch SQLException e
        (log/warn {:uri (:uri req)} (str "db-server: the database refused a statement: "
                                         (.getMessage e)))
        ;; The state and the vendor code travel with the message so the facade
        ;; can rebuild a SQLException that answers `.getSQLState` and
        ;; `.getErrorCode` the way the local one would. A caller that reads
        ;; those is reading the database's own words, and losing them here
        ;; would be a difference between the two handles. The driver's class
        ;; name does NOT travel: the facade cannot construct it, nothing reads
        ;; it, and a field on a wire that nobody reads is a field that will
        ;; quietly stop being true.
        (transit-response 500 {:error      (.getMessage e)
                               :type       :db-server/sql-error
                               :sql?       true
                               :sql-state  (.getSQLState e)
                               :error-code (.getErrorCode e)}))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (transit-response (or (:db-server/status data) 400)
                            {:error (.getMessage e)
                             :type  (or (:type data) :db-server/bad-request)})))
      (catch Throwable t
        (log/error t "db-server: unhandled failure")
        (transit-response 500 {:error (str (.getMessage t)) :type :db-server/error})))))

(defn- app
  [server]
  (routes
    (wrap-refusals
      (routes
        (POST "/execute" req (execute server req))
        (POST "/execute-one" req (execute-one server req))
        (POST "/tx/begin" req (tx-begin server req))
        (POST "/tx/commit" req (tx-commit server req))
        (POST "/tx/rollback" req (tx-rollback server req))))
    (GET "/health" [] (health server))
    (GET "/api/describe" [] (describe))
    (fn [req] (json-response 404 {:error (str "db-server: no such route: " (:uri req))}))))

;; -- process ---------------------------------------------------------------

(defn- check-vec-path!
  "Refuse to boot on a `:vec-path` this process cannot honour.

   `datastore.connection` resolves the extension path once, at load, out of
   `:db-server :vec-path` in config.edn -- the same key `config-opts` reads
   below, so a server booted from the file agrees with it by construction and
   this check has nothing to say. What it is for is a caller that passes a
   *different* path explicitly: the extension is loaded on every connection
   this datasource hands out, and no option here can change that after the
   fact. Accepting the argument and silently ignoring it is the failure this
   whole seam is arranged to prevent, so it is refused instead."
  [vec-path]
  (when (and vec-path (not= vec-path connection/vec-extension-path))
    (throw (ex-info (str "db-server: :vec-path " (pr-str vec-path) " but datastore.connection "
                         "loaded " (pr-str connection/vec-extension-path) " from :db-server "
                         ":vec-path in config.edn. The extension path is resolved once, at "
                         "load; a different one here could not take effect.")
                    {:vec-path vec-path :loaded connection/vec-extension-path}))))

(defn start!
  "Open the database and start answering the protocol on `:port`. Returns a
   server map; hand it back to `stop!`.

   `:port`, `:db-path` and `:vec-path` are the plan's `:db-server` config
   section, so that step 4 can plug that section in whole. The rest are
   operational and not part of it:

   - `:port`        the port to bind; 0 takes an ephemeral one, and the port
                    actually bound comes back as `:port` on the server map.
   - `:db-path`     the SQLite file. Required.
   - `:vec-path`    optional, and only checked -- see `check-vec-path!`.
   - `:read-only?`  open the database read-only, the replica's structural write
                    ban. Schema application is skipped, since a replica's schema
                    arrives with the file it was synced from. Step 4 decides
                    this from the `primary.nosync` marker; here it is explicit.
   - `:tx-idle-ms`  how long an abandoned transaction is left before it is
                    rolled back. Defaults to a minute, and is swept for at
                    quarter-window intervals, so the real window is one to
                    one-and-a-quarter of it.

   There is no `:host`, and passing one is refused rather than ignored -- the
   rule the option whitelist and `check-vec-path!` are both keeping. This
   endpoint runs arbitrary SQL and has no authentication of any kind, and the
   plan says loopback always for this phase; an option would be a way to hand
   the database to the network by passing one argument, and silently dropping
   it would be a caller who thinks he has bound elsewhere and has not. Binding
   beyond the machine comes with the auth that has to arrive alongside it, and
   neither is in this step."
  [{:keys [port db-path vec-path read-only? tx-idle-ms] :as opts}]
  (when (contains? opts :host)
    (throw (ex-info (str "db-server: :host is not an option -- this binds " loopback
                         " and nothing else. These routes run arbitrary SQL with no "
                         "authentication, so exposing them beyond the machine is a step "
                         "that has to arrive with the auth for it.")
                    {:host (:host opts)})))
  (when (str/blank? (str db-path))
    (throw (ex-info "db-server: :db-path is required" {})))
  (when (nil? port)
    (throw (ex-info "db-server: :port is required (0 for an ephemeral one)" {})))
  (check-vec-path! vec-path)
  (let [ds     (connection/make-datasource {:dbname db-path :read-only? (boolean read-only?)})
        server {:ds           ds
                :read-only?   (boolean read-only?)
                :transactions (atom {})
                :tx-idle-ms   (or tx-idle-ms default-tx-idle-ms)}]
    ;; Before anything else, and before the port is open: prove the database is
    ;; actually there. Applying the schema would prove it for a writable one,
    ;; but a read-only server skips that and would otherwise come up green
    ;; against a path that does not exist.
    (try (reach-the-database! server)
         (catch Throwable t
           (throw (ex-info (str "db-server: cannot reach the database at " (pr-str db-path)
                                ": " (.getMessage t))
                           {:db-path db-path :read-only? (boolean read-only?)} t))))
    (if read-only?
      (log/info "db-server: read-only, so the schema is left as it arrived")
      (schema/apply-schema! ds))
    (let [jetty   (jetty/run-jetty (app server) {:port port :host loopback :join? false})
          bound   (.getLocalPort ^ServerConnector (first (.getConnectors jetty)))
          sweeper (doto (Executors/newSingleThreadScheduledExecutor)
                    (.scheduleWithFixedDelay
                      ^Runnable (fn []
                                  (try (sweep-idle-transactions! server)
                                       (catch Throwable t
                                         (log/error t "db-server: idle sweep failed"))))
                      ;; Same first delay as period: a server told to time
                      ;; transactions out quickly should sweep quickly too,
                      ;; which is what lets a test watch it happen.
                      (max 250 (quot (:tx-idle-ms server) 4))
                      (max 250 (quot (:tx-idle-ms server) 4))
                      TimeUnit/MILLISECONDS))]
      (log/info {:port bound :db-path db-path :read-only? (boolean read-only?)}
                "db-server: up")
      (assoc server
        :jetty   jetty
        :sweeper sweeper
        :port    bound
        :url     (str "http://" loopback ":" bound)))))

(defn stop!
  "Stop a server `start!` returned. Every transaction still open is rolled
   back, not left to the sweeper: the process is going away and a held
   connection would take the write lock with it.

   Jetty goes first, and the order is the whole point: while it is still
   answering, a request can open a transaction after the loop has passed, and
   that connection would then be leaked with the write lock in its hand. In a
   process that is exiting anyway that is invisible; in the test harness of step
   3, where a db-server is started and stopped inside the JVM the next test runs
   in, it is a locked database."
  [{:keys [jetty sweeper transactions] :as server}]
  (when jetty (.stop jetty))
  (when sweeper (.shutdownNow sweeper))
  (doseq [token (keys @transactions)]
    (try (close-transaction! server token false true)
         (catch Throwable t (log/error t "db-server: could not roll back on shutdown"))))
  nil)

;; -- boot from config.edn ---------------------------------------------------

(def ^:private config-path "./config.edn")

(def default-port
  "The port when the `:db-server` section names none. onboard.sh writes
   `#long #or [#env DB_PORT 3141]`, so this is only reached by a hand-written
   section; `scripts/detect-ports.sh` falls back to the same number, and it is
   the same number on purpose."
  3141)

(def e2e-db-path
  "The only database a db-server started under the `:e2e` alias may open.

   Before the split, `-Drhizome.e2e=1` picked the file: `config.clj` hardcoded
   it, so an e2e JVM physically could not reach the developer's database. Since
   the split the file is the db-server's `:db-path`, which `scripts/e2e.sh`
   points here by exporting `DB_PATH` -- and an export is a thing that can be
   forgotten. A db-server hand-started with `-M:e2e` and no `DB_PATH` would open
   `./rhizome.db`, and e2e's `globalSetup` POSTs `/test/reset`, which deletes
   every row it can see. So the old guarantee is kept, by refusal rather than by
   hardcoding."
  "./test/rhizome-e2e.db")

(defn- check-e2e-db-path!
  "Refuse to open anything but `e2e-db-path` when this JVM was started under the
   `:e2e` alias. Compared as canonical paths, so `test/rhizome-e2e.db` and
   `./test/rhizome-e2e.db` are the same answer."
  [db-path]
  (when (= "1" (System/getProperty "rhizome.e2e"))
    (let [canon #(.getCanonicalPath (io/file %))]
      (when-not (= (canon db-path) (canon e2e-db-path))
        (throw (ex-info (str "db-server: refusing to open " (pr-str db-path)
                             " under -Drhizome.e2e=1. An e2e run may only touch "
                             (pr-str e2e-db-path) ", because its globalSetup POSTs "
                             "/test/reset and that deletes every row in whatever "
                             "database is behind it. Export DB_PATH=" e2e-db-path
                             " (scripts/e2e.sh does), or start this db-server "
                             "without the :e2e alias.")
                        {:db-path db-path :e2e-db-path e2e-db-path}))))))

(defn config-opts
  "The `:db-server` section of a `config.edn`, as `start!` takes it.

   **It reads that one key, and one flag outside it.** The key is this
   server's entire configuration -- `:port`, `:db-path`, `:vec-path` -- which
   is what makes the two arrangements one file format: the shared config.edn
   the app-server also reads, or a standalone one holding nothing but
   `{:db-server {…}}`. Neither needs a reader of its own.

   The flag is the top-level `:dev?`, and it is read because the primary /
   replica rule is `(and (not dev?) (not marker))` and **both processes have to
   reach the same verdict about the same directory** -- see `role`. A mode the
   whole deployment is in is not this server's private configuration, which is
   why it is not in the section; a standalone file that says nothing is prod,
   which is the right default for a file written for a deployment.

   The two keys that moved into the section are refused by name where they used
   to live. Ignoring them would be silent: an old top-level `:db-path` would
   leave this server pointed at nothing, and an old `:semsearch :vec-path` would
   turn the vec extension off everywhere without a word."
  ([] (config-opts config-path))
  ([path]
   (let [c       (aero/read-config path)
         section (:db-server c)]
     (when (:db-path c)
       (throw (ex-info (str "db-server: :db-path moved into the :db-server section. "
                            "Write :db-server {:db-path \"…\"} in " path ".")
                       {:config-path path})))
     (when (get-in c [:semsearch :vec-path])
       (throw (ex-info (str "db-server: :vec-path moved from :semsearch into the :db-server "
                            "section -- loading the extension is this process's business now. "
                            ":semsearch keeps :ollama-url and :ollama-model, which are the "
                            "app-side embedder's.")
                       {:config-path path})))
     (when-not (map? section)
       (throw (ex-info (str "db-server: no :db-server section in " path
                            ". It needs at least a :db-path; `make onboard` writes the "
                            "whole block.")
                       {:config-path path})))
     (check-e2e-db-path! (:db-path section))
     {:port       (or (:port section) default-port)
      :db-path    (:db-path section)
      :vec-path   (:vec-path section)
      :read-only? (role/read-only-replica? c (role/primary-marker-present?))})))

(defn -main
  "Start the db-server from the config.edn in the directory it was launched in,
   and stay up.

   There is nothing to join and no daemon flag anywhere. Jetty's thread pool is
   not made of daemon threads, so the JVM stays alive after this returns -- and
   so does the sweeper's executor, which is the same reason `clj -X:test` does
   not exit once a test has booted one of these. That was measured when a
   `:daemon? true` option was proposed and then withdrawn; see step 3's handoff.

   `stop!` hangs on a shutdown hook rather than being left to the process dying,
   so a `kill` from `scripts/stop.sh` rolls back whatever transaction was open
   instead of taking SQLite's write lock down with it. `start!` installs no hook
   of its own: the test harness boots servers inside a JVM that goes on running,
   and hangs its own."
  [& _args]
  (let [server (start! (config-opts))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (stop! server))))
    (log/info {:url (:url server)}
              (str "db-server: listening on " (:url server) " -- that is the url the "
                   "app-server derives, and nothing off this machine can reach it."))
    nil))

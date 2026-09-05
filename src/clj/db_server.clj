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

   Boot with `start!` and an opts map shaped like the plan's `:db-server`
   config section; `stop!` takes what it returns. Reading that section out of
   `config.edn`, and the `-main` that would do it, are step 4: nothing here
   reads configuration, which is what lets a test boot as many of these as it
   likes on ephemeral ports."
  (:require [cambium.core :as log]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [compojure.core :refer [GET POST routes]]
            [datastore.connection :as connection]
            [datastore.schema :as schema]
            [db :as db]
            [next.jdbc :as jdbc]
            [ring.adapter.jetty :as jetty])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.sql Connection SQLException]
           [java.util.concurrent Executors TimeUnit]
           [javax.sql DataSource]
           [org.eclipse.jetty.server ServerConnector]))

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
      (swap! transactions assoc token {:conn conn :touched-ms (System/currentTimeMillis)})
      token
      (catch Throwable t
        (try (.close ^Connection conn) (catch Throwable _))
        (throw t)))))

(defn- close-transaction!
  "Commit or roll back the transaction `token` names and free its connection.
   False when there is no such transaction. The entry is taken out of the
   registry first, so the idle sweeper and a `/tx/commit` racing for the same
   token cannot both close the connection."
  [{:keys [transactions]} token commit?]
  (let [[before _] (swap-vals! transactions dissoc token)]
    (if-let [^Connection conn (:conn (get before token))]
      (do (try (if commit? (.commit conn) (.rollback conn))
               (finally (try (.close conn) (catch Throwable _))))
          true)
      false)))

(defn- touch-transaction!
  "The connection `token` names, its idle clock reset. Nil when unknown."
  [{:keys [transactions]} token]
  (let [now (System/currentTimeMillis)
        m   (swap! transactions
                   (fn [m] (if (contains? m token)
                             (assoc-in m [token :touched-ms] now)
                             m)))]
    (:conn (get m token))))

(defn sweep-idle-transactions!
  "Roll back and free every transaction untouched for longer than the idle
   timeout, and answer how many there were.

   Run on a timer by `start!`; public so that an operator at a REPL can free a
   stuck transaction without waiting the minute out. The tests do not call it
   -- what they watch is the timer."
  [{:keys [transactions tx-idle-ms] :as server}]
  (let [cutoff (- (System/currentTimeMillis) tx-idle-ms)
        stale  (vec (keep (fn [[token {:keys [touched-ms]}]]
                            (when (< touched-ms cutoff) token))
                          @transactions))]
    (doseq [token stale]
      (log/warn {:tx token :idle-ms tx-idle-ms}
                "db-server: rolling back a transaction nobody came back for")
      (try (close-transaction! server token false)
           (catch Throwable t
             (log/error t "db-server: could not roll back an idle transaction"))))
    (count stale)))

(defn- transaction-connection!
  [server token]
  (or (touch-transaction! server token)
      (throw (ex-info (str "db-server: no such transaction: " token
                           " -- it was committed, rolled back, or left idle too long")
                      {:db-server/status 410 :type :db-server/unknown-transaction}))))

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

(defn- run-statement
  [server one? req]
  (let [{:keys [stmt opts tx]} (transit-body req)
        statement (check-stmt! stmt)
        options   (db/jdbc-opts (or opts {}))
        target    (if tx (transaction-connection! server tx) (:ds server))]
    (transit-response 200 {:result (if one?
                                     (jdbc/execute-one! target statement options)
                                     (jdbc/execute! target statement options))})))

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
    (when-not (close-transaction! server tx true)
      (throw (ex-info (str "db-server: no such transaction: " tx)
                      {:db-server/status 410 :type :db-server/unknown-transaction})))
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
    (when-not (close-transaction! server tx false)
      (throw (ex-info (str "db-server: no such transaction: " tx)
                      {:db-server/status 410 :type :db-server/unknown-transaction})))
    (transit-response 200 {:ok true})))

(defn ^:endpoint health
  "GET /health — `{:ok true :read-only? b :vec-available? b}`, as JSON.

  What a start script waits on and what a prober reads, so it answers plain
  JSON rather than transit and says everything either of them needs in one
  line. `:read-only?` is whether this process opened the database read-only --
  a replica, where every write fails at the driver. `:vec-available?` is
  whether the sqlite-vec extension loaded, which is what decides whether the
  items_vec statements can run at all."
  [server]
  (json-response 200 {:ok             true
                      :read-only?     (boolean (:read-only? server))
                      :vec-available? connection/vec-available?}))

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
        (transit-response 500 {:error (.getMessage e)
                               :type  :db-server/sql-error
                               :sql?  true
                               :class (.getName (class e))}))
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
   config.edn -- it takes no argument yet, and giving it one is step 4's job
   along with moving the key into the `:db-server` section. Until then the
   option is accepted for the shape of the map and checked against what was
   actually loaded, because the alternative is accepting it and silently
   ignoring it, which is the failure this whole seam is arranged to prevent."
  [vec-path]
  (when (and vec-path (not= vec-path connection/vec-extension-path))
    (throw (ex-info (str "db-server: :vec-path " (pr-str vec-path) " but datastore.connection "
                         "loaded " (pr-str connection/vec-extension-path) ". The extension path "
                         "is still read from config.edn at load time; moving it into the "
                         ":db-server section is step 4.")
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
   - `:host`        defaults to 127.0.0.1, and there is no reason yet to pass
                    anything else: this plan never exposes the seam beyond the
                    machine.
   - `:tx-idle-ms`  how long an abandoned transaction is left before it is
                    rolled back. Defaults to a minute."
  [{:keys [port db-path vec-path read-only? host tx-idle-ms]}]
  (when (str/blank? (str db-path))
    (throw (ex-info "db-server: :db-path is required" {})))
  (when (nil? port)
    (throw (ex-info "db-server: :port is required (0 for an ephemeral one)" {})))
  (check-vec-path! vec-path)
  (let [host   (or host "127.0.0.1")
        ds     (connection/make-datasource {:dbname db-path :read-only? (boolean read-only?)})
        server {:ds           ds
                :read-only?   (boolean read-only?)
                :transactions (atom {})
                :tx-idle-ms   (or tx-idle-ms default-tx-idle-ms)}]
    (if read-only?
      (log/info "db-server: read-only, so the schema is left as it arrived")
      (schema/apply-schema! ds))
    (let [jetty   (jetty/run-jetty (app server) {:port port :host host :join? false})
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
        :url     (str "http://" host ":" bound)))))

(defn stop!
  "Stop a server `start!` returned. Every transaction still open is rolled
   back, not left to the sweeper: the process is going away and a held
   connection would take the write lock with it."
  [{:keys [jetty sweeper transactions] :as server}]
  (when sweeper (.shutdownNow sweeper))
  (doseq [token (keys @transactions)]
    (try (close-transaction! server token false)
         (catch Throwable t (log/error t "db-server: could not roll back on shutdown"))))
  (when jetty (.stop jetty))
  nil)

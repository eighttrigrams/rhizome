(ns db
  "The seam between the app and the database: every statement the app runs
   goes through here.

   A handle is one of two things, and every function here dispatches on which:

   - **local** -- a `javax.sql.DataSource`, or the `java.sql.Connection` a
     transaction is holding. Handed straight to next.jdbc, byte for byte the
     behaviour the call sites had while they were calling next.jdbc themselves.
   - **remote** -- `{:db-server/url \"http://127.0.0.1:3141\"}`, and inside a
     transaction that map plus `{:db-server/tx \"<token>\"}`. Transit over HTTP
     to `db-server`, which holds the database at the other end.

   The point of the dispatch is that no caller above this namespace knows which
   it has. That is not only a matter of the same values coming back: the same
   call has to *fail* the same way too, or code that catches something would
   quietly work on one side of the wire and not the other. So a statement the
   database refuses is a `SQLException` whichever handle ran it, an option this
   seam does not carry is refused in the same words before it is ever sent, and
   nesting a transaction is the same `IllegalStateException` with the same
   message on both. See `plans/split-db-server.md` and `specs/architecture.md`.

   Two things about the shape of it, and they are why this is not simply an
   alias for next.jdbc:

   - A statement is a plain `[sql & params]` vector. Every call site already
     produces one, out of honeysql's `sql/format` or written literally, so
     the whole surface is data that can be put on a wire as it stands.
   - The options are named as data (`{:builder :unqualified-lower}`), not
     handed over as next.jdbc's option map. `:builder-fn` is a *function*,
     which is exactly the thing that cannot cross a process boundary; the
     keyword is looked up in a whitelist here, and on the db-server by this
     namespace's own `jdbc-opts`. Anything not on the list is refused
     rather than passed along, so an option that works locally and would
     silently stop working remotely cannot get in."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-http.conn-mgr :as conn-mgr]
            [cognitect.transit :as transit]
            [datastore.connection :as connection]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.transaction :as jdbc-tx])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.sql SQLException]))

(def ^:private builders
  "The result-set builders a caller may ask for, by name. Named rather than
   passed, because the name is what travels: code does not."
  {:unqualified-lower rs/as-unqualified-lower-maps})

(def ^:private option-keys
  "Every option this seam carries, listed exhaustively. next.jdbc understands
   a good many more, and one of those slipping through here is precisely the
   bug this list exists to prevent: it would work perfectly against a local
   DataSource and be dropped on the floor the day the handle is remote."
  #{:builder :return-keys})

(defn- check-opts!
  "Refuse anything the seam does not carry, and hand `opts` back. Run on both
   sides of the wire and on neither's trust: the client checks so that a bad
   option fails identically whether the handle is local or remote, and the
   db-server checks because a server may not believe its callers."
  [opts]
  (when-let [unknown (seq (remove option-keys (keys opts)))]
    (throw (ex-info (str "db: unsupported statement option(s) " (pr-str (vec unknown)))
                    {:opts opts :supported option-keys})))
  (when-let [builder (:builder opts)]
    (when-not (contains? builders builder)
      (throw (ex-info (str "db: unknown result-set builder " (pr-str builder))
                      {:builder builder :supported (set (keys builders))}))))
  opts)

(defn jdbc-opts
  "The next.jdbc option map for a seam option map, the `:builder` name resolved
   to the function it stands for.

   Public because `db-server` runs the statements that arrive over the wire and
   has to resolve them against this same whitelist -- one list, read by both
   ends, so the two cannot drift. `test/unit/db_test.clj` pins it literally."
  [opts]
  (check-opts! opts)
  (if-let [builder (:builder opts)]
    (-> opts
        (dissoc :builder)
        (assoc :builder-fn (get builders builder)))
    opts))

;; -- remote handles --------------------------------------------------------

(defn remote?
  "Whether `handle` names a db-server over HTTP rather than a local
   DataSource or Connection. A local handle is a Java object and never a map,
   so the two can be told apart by shape alone."
  [handle]
  (and (map? handle) (contains? handle :db-server/url)))

(def ^:private transit-type "application/transit+json")

(defn- write-transit ^bytes [v]
  (let [os (ByteArrayOutputStream. 1024)]
    (transit/write (transit/writer os :json) v)
    (.toByteArray os)))

(defn- read-transit [^bytes b]
  (transit/read (transit/reader (ByteArrayInputStream. b) :json)))

(defonce ^:private connections
  ;; One pooled manager for the whole process. The chattiest path is
  ;; search-per-keystroke, a handful of statements each, and a fresh TCP
  ;; connection per statement is the one cost this seam could plausibly add.
  ;;
  ;; Eight per host is the number that matters in the app, where there is one
  ;; db-server and a single user in front of it. The generous total is for the
  ;; test suite, which boots a db-server per test on its own ephemeral port:
  ;; each one is a separate host to the pool, and the idle timeout is what
  ;; eventually closes the sockets of the ones that have been stopped.
  (delay (conn-mgr/make-reusable-conn-manager {:timeout 30 :threads 32 :default-per-route 8})))

(def ^:private request-defaults
  "How long to wait to *reach* the db-server, no limit on how long it may then
   take to answer, and no retries.

   All three are deliberate. A db-server that is not there -- not started
   yet, stopped, or on a machine that is gone -- must fail rather than hang the
   thread that asked, and over loopback a connection is either immediate or
   never. But a statement has no bound worth guessing at: contention alone
   costs the driver's three seconds, a backfill sweep costs more, and a socket
   timeout is a stopwatch on the database's work that would turn a slow query
   into a failure. If a hung db-server ever needs a ceiling, it wants one that
   knows what statement it is timing, which is not this.

   Retries are off. Apache HttpClient retries a request whose connection died without answering, on the reasoning
   that a pooled connection the server had already closed never delivered it --
   usually true, and unknowable from this end. Every request here is a
   statement, so the case where it is wrong is a write that ran and is run
   again. A visible error on a stale connection is the better trade: this seam
   exists to keep failures from being silent, and a duplicated INSERT is as
   silent as they come."
  {:connection-timeout         2000
   :connection-request-timeout 5000
   :retry-handler              (fn [_ex _try-count _context] false)})

(defn- fail!
  "Turn a non-2xx answer into the exception the caller would have got locally.

   A statement the *database* refused comes back marked `:sql?`, and is rethrown
   as a `SQLException` -- the type a local handle raises for the same statement,
   so a caller that discriminates on it cannot tell which side of the wire it is
   on. Everything else is the protocol's own refusal and stays an ex-info."
  [url path {:keys [status body]}]
  (let [answer (try (read-transit body) (catch Throwable _ nil))
        msg    (or (:error answer)
                   (str "db-server " path " answered " status))]
    (if (:sql? answer)
      (throw (SQLException. ^String msg
                            ^String (:sql-state answer)
                            ^int (int (or (:error-code answer) 0))))
      (throw (ex-info msg (cond-> {:db-server/url url :db-server/path path :status status}
                            (:type answer) (assoc :type (:type answer))))))))

(defn- post!
  [handle path body]
  (let [url  (str (:db-server/url handle) path)
        resp (http/post url (merge request-defaults
                                   {:body               (write-transit body)
                                    :content-type       transit-type
                                    :accept             transit-type
                                    :as                 :byte-array
                                    :throw-exceptions   false
                                    :connection-manager @connections}))]
    (if (<= 200 (:status resp) 299)
      (read-transit (:body resp))
      (fail! (:db-server/url handle) path resp))))

(def ^:private health-ttl-ms
  "How long a `/health` answer is believed.

   It cannot be forever, which is what this was. The db-server is a process
   that can be restarted on its own -- that is the whole point of it being a
   process -- and the answer it gives is read once at ITS startup, so the two
   go out of step exactly when someone installs the vec extension and restarts
   the inner server. The app would then go on saying `vec-available? false`
   until it was restarted too, and `et.vp.ds/clear-item-embedding!` would stop
   deleting superseded embeddings, with nothing failing anywhere: semantic
   search matching text that is no longer in the item. That is the silent
   failure this seam exists to prevent, so the cache gets a lifetime.

   A minute, because the caller is `clear-item-embedding!` on every description
   save and a round trip per save is what the cache is for."
  60000)

(defonce ^:private remote-health (atom {}))

(defn- ask-health
  [url]
  (let [resp (http/get (str url "/health")
                       (merge request-defaults {:as :string :throw-exceptions false
                                        :connection-manager @connections}))]
    (when-not (<= 200 (:status resp) 299)
      (throw (ex-info (str "db-server /health answered " (:status resp))
                      {:db-server/url url :status (:status resp)})))
    (json/parse-string (:body resp) true)))

(defn- health
  "The db-server's `/health`, remembered per url for `health-ttl-ms`. JSON
   rather than transit: this is the route a start script waits on and a prober
   reads, and neither of those should have to speak the statement protocol.

   Nothing is cached that was not answered: a db-server that cannot be reached,
   or that answers 503 because it cannot reach its own database, throws here and
   is asked again next time."
  [handle]
  (let [url   (:db-server/url handle)
        now   (System/currentTimeMillis)
        entry (get @remote-health url)]
    (if (and entry (< (- now (:at entry)) health-ttl-ms))
      (:answer entry)
      (let [answer (ask-health url)]
        (swap! remote-health assoc url {:at now :answer answer})
        answer))))

(defn- remote-vec-available? [handle] (boolean (:vec-available? (health handle))))

(defn- remote-execute
  [handle path stmt opts]
  (check-opts! opts)
  (:result (post! handle path (cond-> {:stmt (vec stmt) :opts opts}
                                (:db-server/tx handle) (assoc :tx (:db-server/tx handle))))))

(defn execute!
  "Run `stmt` -- `[sql & params]` -- and return every row."
  ([handle stmt] (execute! handle stmt {}))
  ([handle stmt opts]
   (if (remote? handle)
     (remote-execute handle "/execute" stmt opts)
     (jdbc/execute! handle stmt (jdbc-opts opts)))))

(defn execute-one!
  "Run `stmt` -- `[sql & params]` -- and return the first row (or, for a
   statement that returns none, its update count)."
  ([handle stmt] (execute-one! handle stmt {}))
  ([handle stmt opts]
   (if (remote? handle)
     (remote-execute handle "/execute-one" stmt opts)
     (jdbc/execute-one! handle stmt (jdbc-opts opts)))))

(defn- rollback-after!
  "Roll `token` back because `t` came out of the body, and then throw `t`.

   A rollback the db-server refuses *because the token names nothing* is not a
   rollback failure. There is nothing left to roll back: the transaction was
   already ended -- swept for being idle, or closed by a commit that threw --
   and the exception worth carrying is the one that started this. Reporting a
   rollback failure there would replace a true error with a false one, which is
   how a SQLITE_BUSY on commit used to reach the app as
   `Rollback failed handling ...`.

   A rollback that fails for any other reason is reported the way next.jdbc
   reports it, carrying both exceptions rather than losing the first."
  [handle token t]
  (try (post! handle "/tx/rollback" {:tx token})
       (catch Throwable rb
         (when-not (= :db-server/unknown-transaction (:type (ex-data rb)))
           (throw (ex-info (str "Rollback failed handling \"" (.getMessage t) "\"")
                           {:rollback rb :handling t})))))
  (throw t))

(defn- remote-transact
  "A transaction over the wire: a token opened before the body, and committed
   or rolled back after it.

   The refusal at the top is the remote half of `transact`'s `:prohibit`, and
   it throws the very exception next.jdbc throws locally -- same class, same
   message -- because a caller must not be able to tell from the failure which
   side of the wire its handle was on. The db-server refuses a `/tx/begin` that
   carries a token as well; this one is here so the refusal costs no round trip
   and reads identically to the local one.

   The commit is deliberately outside the body's catch. A commit the database
   refuses is already rolled back and closed on the far side -- `db-server`'s
   `finish!` does that, which is what next.jdbc does locally -- so there is
   nothing here to compensate for, and the exception the caller gets is the
   database's own, with its message, its SQLSTATE and its vendor code intact."
  [handle f]
  (when (:db-server/tx handle)
    (throw (IllegalStateException. "Nested transactions are prohibited")))
  (let [token     (:tx (post! handle "/tx/begin" {}))
        tx-handle (assoc handle :db-server/tx token)
        result    (try (f tx-handle)
                       (catch Throwable t (rollback-after! handle token t)))]
    (post! handle "/tx/commit" {:tx token})
    result))

(defn transact
  "`with-transaction` with the body as a one-argument function. Public because
   the macro below expands into it, and a function because that is the form the
   dispatch will take: a remote transaction is a token opened before the body
   and closed after it, which is a call around a call rather than something a
   macro can stay all the way down.

   Nesting is prohibited rather than allowed. next.jdbc's default is `:allow`,
   and `:allow` is a trap: opening a second transaction on a handle that is
   already one COMMITS the outer transaction when the inner one ends. An outer
   transaction that then throws leaves everything it wrote before the inner one
   standing -- a partial commit, with nothing raised anywhere to say so. No
   call path nests today (see db-test); this is so that the day one does, it
   says so instead of half-writing.

   next.jdbc detects this per Connection object, which is exactly the case
   worth refusing -- handing a `tx` back into `with-transaction` -- and not an
   independent transaction taken on the DataSource, which borrows a connection
   of its own and is unaffected. That guard is tied to a Connection and does
   not survive the wire, so `remote-transact` and the db-server's `/tx/begin`
   enforce the same rule for tokens themselves, and fail the same way."
  [handle f]
  (if (remote? handle)
    (remote-transact handle f)
    (binding [jdbc-tx/*nested-tx* :prohibit]
      (jdbc/with-transaction [tx handle] (f tx)))))

(defmacro with-transaction
  "Run `body` with `sym` bound to a handle inside a transaction on `handle`,
   committing when it returns and rolling back when it throws.

   Every statement in the body has to be run against `sym` and not against
   the handle the transaction was opened on. That is true of next.jdbc's
   transactions already, and is about to be true in a harder way: `sym` is a
   handle rather than necessarily a Connection, and once a handle can be
   remote it is the thing carrying the transaction's token.

   The binding is exactly two forms, and anything else is refused here rather
   than accepted and quietly ignored. next.jdbc's binding takes an optional
   third -- `{:isolation … :read-only … :rollback-only …}` -- and a facade that
   dropped one on the floor would be the very bug the statement-option
   whitelist above exists to prevent, one level up: it would read as working
   and change nothing. No call site passes one; the seam carries none of them,
   and the step-2 protocol has nowhere to put them."
  [binding-form & body]
  (when-not (vector? binding-form)
    (throw (ex-info (str "db/with-transaction wants a vector binding, [sym handle] -- got "
                         (pr-str binding-form))
                    {:binding binding-form})))
  (let [[sym handle & more] binding-form]
    (when (seq more)
      (throw (ex-info (str "db/with-transaction takes no transaction options: "
                           (pr-str (vec more)) " would be dropped here, and there is "
                           "nothing on the wire to carry it in step 2.")
                      {:binding binding-form :options (vec more)})))
    (when-not (= 2 (count binding-form))
      (throw (ex-info (str "db/with-transaction wants a binding of exactly two forms, "
                           "[sym handle] -- got " (pr-str binding-form))
                      {:binding binding-form})))
    `(transact ~handle (fn [~sym] ~@body))))

(defn vec-available?
  "Whether the database behind this handle can run the sqlite-vec SQL --
   `items_vec`, `vec_distance_cosine` -- so that a caller can leave those
   statements out rather than have them fail.

   Asked of the handle rather than read off the config because it is a fact
   about the database, not about the process asking. For a local handle that is
   the filesystem check `datastore.connection` made at startup; for a remote one
   the dylib is on the far side of the wire, so the answer comes off the
   db-server's `/health`.

   Cached per db-server for a minute, not for the life of the process: the
   db-server can be restarted under a running app, and an answer believed
   forever would be wrong from then on. See `health-ttl-ms`.

   This is the one question at the seam a remote handle can fail to answer.
   Everywhere else a local handle can throw too -- a statement can always be
   refused -- but this one is a `def` locally and a round trip remotely, so a
   db-server that is not reachable turns a question that could not fail into one
   that can. The caller is `et.vp.ds/clear-item-embedding!`, which runs inside
   a description save; a db-server that is down fails that save, which is what
   it was going to do at the next statement anyway."
  [handle]
  (if (remote? handle)
    (remote-vec-available? handle)
    connection/vec-available?))

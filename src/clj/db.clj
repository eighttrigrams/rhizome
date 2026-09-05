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
      (throw (SQLException. ^String msg))
      (throw (ex-info msg (cond-> {:db-server/url url :db-server/path path :status status}
                            (:type answer) (assoc :type (:type answer))))))))

(defn- post!
  [handle path body]
  (let [url  (str (:db-server/url handle) path)
        resp (http/post url {:body               (write-transit body)
                             :content-type       transit-type
                             :accept             transit-type
                             :as                 :byte-array
                             :throw-exceptions   false
                             :connection-manager @connections})]
    (if (<= 200 (:status resp) 299)
      (read-transit (:body resp))
      (fail! (:db-server/url handle) path resp))))

(defonce ^:private remote-health (atom {}))

(defn- health
  "The db-server's `/health`, read once per url and remembered. JSON rather
   than transit: this is the route a start script waits on and a prober reads,
   and neither of those should have to speak the statement protocol."
  [handle]
  (let [url (:db-server/url handle)]
    (or (get @remote-health url)
        (let [resp (http/get (str url "/health")
                             {:as :string :throw-exceptions false
                              :connection-manager @connections})]
          (when-not (<= 200 (:status resp) 299)
            (throw (ex-info (str "db-server /health answered " (:status resp))
                            {:db-server/url url :status (:status resp)})))
          (let [answer (json/parse-string (:body resp) true)]
            (swap! remote-health assoc url answer)
            answer)))))

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

(defn- remote-transact
  "A transaction over the wire: a token opened before the body, and committed
   or rolled back after it.

   The refusal at the top is the remote half of `transact`'s `:prohibit`, and
   it throws the very exception next.jdbc throws locally -- same class, same
   message -- because a caller must not be able to tell from the failure which
   side of the wire its handle was on. The db-server refuses a `/tx/begin` that
   carries a token as well; this one is here so the refusal costs no round trip
   and reads identically to the local one.

   A rollback that itself fails is reported the way next.jdbc reports it, with
   both exceptions, rather than losing the one that started it."
  [handle f]
  (when (:db-server/tx handle)
    (throw (IllegalStateException. "Nested transactions are prohibited")))
  (let [token     (:tx (post! handle "/tx/begin" {}))
        tx-handle (assoc handle :db-server/tx token)]
    (try
      (let [result (f tx-handle)]
        (post! handle "/tx/commit" {:tx token})
        result)
      (catch Throwable t
        (try (post! handle "/tx/rollback" {:tx token})
             (catch Throwable rb
               (throw (ex-info (str "Rollback failed handling \"" (.getMessage t) "\"")
                               {:rollback rb :handling t}))))
        (throw t)))))

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

   Cached per db-server, and for the life of the process. The far side's answer
   is a `def` read once at ITS startup and cannot change while it is running, so
   there is nothing to re-ask; and this is asked on every description save
   (`et.vp.ds/clear-item-embedding!`), which is not a place to put a round trip."
  [handle]
  (if (remote? handle)
    (remote-vec-available? handle)
    connection/vec-available?))

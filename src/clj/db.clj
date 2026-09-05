(ns db
  "The seam between the app and the database: every statement the app runs
   goes through these four functions.

   Today it is a passthrough. A handle is a `javax.sql.DataSource` (or, inside
   a transaction, the `java.sql.Connection` that transaction is holding), and
   each function hands it straight to next.jdbc -- byte for byte the behaviour
   the call sites had while they were calling next.jdbc themselves. Nothing
   above this namespace changed except the name it calls.

   The indirection is for the step after this one. A handle may then also name
   a db-server over HTTP, and these same four functions dispatch on which kind
   of handle they were given, so that no caller above the seam has to know
   which side of a process boundary its database is on. See
   `plans/split-db-server.md` and `specs/architecture.md`.

   Two things follow from that, and they are why this is not simply an alias
   for next.jdbc:

   - A statement is a plain `[sql & params]` vector. Every call site already
     produces one, out of honeysql's `sql/format` or written literally, so
     the whole surface is data that can be put on a wire as it stands.
   - The options are named as data (`{:builder :unqualified-lower}`), not
     handed over as next.jdbc's option map. `:builder-fn` is a *function*,
     which is exactly the thing that cannot cross a process boundary; the
     keyword is looked up in a whitelist here, and will be looked up in the
     same whitelist on the db-server. Anything not on the list is refused
     rather than passed along, so an option that works locally and would
     silently stop working remotely cannot get in."
  (:require [datastore.connection :as connection]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

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

(defn- jdbc-opts
  "The next.jdbc option map for a seam option map."
  [opts]
  (when-let [unknown (seq (remove option-keys (keys opts)))]
    (throw (ex-info (str "db: unsupported statement option(s) " (pr-str (vec unknown)))
                    {:opts opts :supported option-keys})))
  (if-let [builder (:builder opts)]
    (if-let [builder-fn (get builders builder)]
      (-> opts
          (dissoc :builder)
          (assoc :builder-fn builder-fn))
      (throw (ex-info (str "db: unknown result-set builder " (pr-str builder))
                      {:builder builder :supported (set (keys builders))})))
    opts))

(defn execute!
  "Run `stmt` -- `[sql & params]` -- and return every row."
  ([handle stmt] (execute! handle stmt {}))
  ([handle stmt opts] (jdbc/execute! handle stmt (jdbc-opts opts))))

(defn execute-one!
  "Run `stmt` -- `[sql & params]` -- and return the first row (or, for a
   statement that returns none, its update count)."
  ([handle stmt] (execute-one! handle stmt {}))
  ([handle stmt opts] (jdbc/execute-one! handle stmt (jdbc-opts opts))))

(defn transact
  "`with-transaction` with the body as a one-argument function. Public because
   the macro below expands into it, and a function because that is the form the
   dispatch will take: a remote transaction is a token opened before the body
   and closed after it, which is a call around a call rather than something a
   macro can stay all the way down."
  [handle f]
  (jdbc/with-transaction [tx handle] (f tx)))

(defmacro with-transaction
  "Run `body` with `sym` bound to a handle inside a transaction on `handle`,
   committing when it returns and rolling back when it throws.

   Every statement in the body has to be run against `sym` and not against
   the handle the transaction was opened on. That is true of next.jdbc's
   transactions already, and is about to be true in a harder way: `sym` is a
   handle rather than necessarily a Connection, and once a handle can be
   remote it is the thing carrying the transaction's token."
  [[sym handle] & body]
  `(transact ~handle (fn [~sym] ~@body)))

(defn vec-available?
  "Whether the database behind this handle can run the sqlite-vec SQL --
   `items_vec`, `vec_distance_cosine` -- so that a caller can leave those
   statements out rather than have them fail.

   Asked of the handle rather than read off the config because it is a fact
   about the database, not about the process asking. Today the two coincide:
   the dylib is loaded by this process's own datasource, so the answer is the
   filesystem check `datastore.connection` made at startup. Once the database
   is behind a db-server, the dylib is on that side of the wire and the answer
   comes from there."
  [_handle]
  connection/vec-available?)

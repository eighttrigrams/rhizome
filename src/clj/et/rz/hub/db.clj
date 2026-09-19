(ns et.rz.hub.db
  "The seam between the app and the database: every statement the app runs goes
   through here.

   A handle is a `javax.sql.DataSource`, or the `java.sql.Connection` a
   transaction is holding, and it is handed straight to next.jdbc.

   ## What this was, and why it is still here

   Until step 4 of the architecture rework a handle was one of *two* things, and
   every function dispatched on which: a local DataSource, or
   `{:db-server/url \"…\"}` -- transit over HTTP to a `db-server` that held the
   database at the other end and ran statements sent to it as SQL. The whole
   point was that no caller above this namespace knew which it had.

   That seam moved. The hub now answers `/ui` and `/api` -- calls about items,
   not statements -- and the only process that runs a statement is the one
   holding the file. So the remote half is gone: the protocol, the transaction
   tokens, the health cache, and the careful work of making a remote failure
   indistinguishable from a local one. It is all in the history if the question
   ever comes back.

   What survives is the reason this was not simply an alias for next.jdbc in the
   first place, and it survives on its own merits:

   - **A statement is a plain `[sql & params]` vector.** Every call site already
     produces one, out of honeysql's `sql/format` or written literally.
   - **The options are named as data** (`{:builder :unqualified-lower}`) rather
     than handed over as next.jdbc's option map, and anything not on the list is
     refused rather than passed along. `:builder-fn` is a *function*; naming it
     instead keeps the call sites declarative and keeps this namespace the one
     place that decides what a call site may ask for."
  (:require [et.rz.hub.sqlite.connection :as connection]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.transaction :as jdbc-tx]))

(def ^:private builders
  "The result-set builders a caller may ask for, by name."
  {:unqualified-lower rs/as-unqualified-lower-maps})

(def ^:private option-keys
  "Every option this seam carries, listed exhaustively. next.jdbc understands a
   good many more, and the list is what keeps a call site from reaching past
   this namespace for one of them without anybody deciding to let it."
  #{:builder :return-keys})

(defn- check-opts!
  "Refuse anything the seam does not carry, and hand `opts` back."
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
   to the function it stands for. `test/unit/db_test.clj` pins it literally."
  [opts]
  (check-opts! opts)
  (if-let [builder (:builder opts)]
    (-> opts
        (dissoc :builder)
        (assoc :builder-fn (get builders builder)))
    opts))

(defn execute!
  "Run `stmt` -- `[sql & params]` -- and return every row."
  ([handle stmt] (execute! handle stmt {}))
  ([handle stmt opts]
   (jdbc/execute! handle stmt (jdbc-opts opts))))

(defn execute-one!
  "Run `stmt` -- `[sql & params]` -- and return the first row (or, for a
   statement that returns none, its update count)."
  ([handle stmt] (execute-one! handle stmt {}))
  ([handle stmt opts]
   (jdbc/execute-one! handle stmt (jdbc-opts opts))))

(defn transact
  "`with-transaction` with the body as a one-argument function. Public because
   the macro below expands into it.

   Nesting is prohibited rather than allowed. next.jdbc's default is `:allow`,
   and `:allow` is a trap: opening a second transaction on a handle that is
   already one COMMITS the outer transaction when the inner one ends. An outer
   transaction that then throws leaves everything it wrote before the inner one
   standing -- a partial commit, with nothing raised anywhere to say so. No call
   path nests today (see et.rz.hub.db-test); this is so that the day one does, it says so
   instead of half-writing.

   next.jdbc detects this per Connection object, which is exactly the case worth
   refusing -- handing a `tx` back into `with-transaction` -- and not an
   independent transaction taken on the DataSource, which borrows a connection
   of its own and is unaffected."
  [handle f]
  (binding [jdbc-tx/*nested-tx* :prohibit]
    (jdbc/with-transaction [tx handle] (f tx))))

(defmacro with-transaction
  "Run `body` with `sym` bound to a handle inside a transaction on `handle`,
   committing when it returns and rolling back when it throws.

   Every statement in the body has to be run against `sym` and not against the
   handle the transaction was opened on. That is true of next.jdbc's
   transactions already.

   The binding is exactly two forms, and anything else is refused here rather
   than accepted and quietly ignored. next.jdbc's binding takes an optional
   third -- `{:isolation … :read-only … :rollback-only …}` -- and a facade that
   dropped one on the floor would be the very bug the statement-option
   whitelist above exists to prevent, one level up: it would read as working and
   change nothing. No call site passes one."
  [binding-form & body]
  (when-not (vector? binding-form)
    (throw (ex-info (str "db/with-transaction wants a vector binding, [sym handle] -- got "
                         (pr-str binding-form))
                    {:binding binding-form})))
  (let [[sym handle & more] binding-form]
    (when (seq more)
      (throw (ex-info (str "db/with-transaction takes no transaction options: "
                           (pr-str (vec more)) " would be dropped here.")
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

   Takes the handle, though it no longer reads it: the question is about the
   database and not about the process asking, and the signature says so. It is
   the filesystem check `et.rz.hub.sqlite.connection` made at startup.

   It used to be a round trip when the handle was remote -- the dylib was on the
   far side of the wire -- which made this the one question at the seam that
   could fail. It cannot any more."
  [_handle]
  connection/vec-available?)

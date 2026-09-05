(ns db-harness
  "The db-server the integration suites run against, and the remote handle onto
   it. Started once for the whole run, on an ephemeral port, and stopped when
   the JVM goes.

   **Two names onto one database.** The database is the same shared-cache
   in-memory SQLite the suite has always used -- `config/config`'s `:db` -- and
   after this there are two ways to reach it:

   - `config/config`'s DataSource, which is what every test's own setup
     statements and assertions keep using, verbatim. `reset-db` included.
   - `remote` here, which is what the harness hands to the app. Statements sent
     through it leave this process over HTTP and come back.

   That is the whole of the arrangement, and it is what lets the existing tests
   go end to end without a line of any test body changing. The alternative --
   one handle, remote, for everything -- would have meant rewriting 88
   statements across 19 files, which is the requirement this exists to keep.

   The dbname is read off the datasource rather than written down again, so the
   two names cannot drift onto two databases: whatever `config` decided, this
   opens the same one."
  (:require [clojure.string :as str]
            [config :as config]
            [db-server])
  (:import [org.sqlite SQLiteDataSource]))

(defn dbname-of
  "The SQLite dbname behind a datasource. `datastore.connection` may hand back
   its vec-loading wrapper rather than the SQLiteDataSource itself, and that
   wrapper implements `unwrap` for exactly this kind of question.

   Public because anything else that wants to open the database the suite is
   running on should ask it the same way rather than writing the name down a
   second time."
  [ds]
  (let [inner (if (instance? SQLiteDataSource ds)
                ds
                (.unwrap ds SQLiteDataSource))]
    (str/replace-first (.getUrl ^SQLiteDataSource inner) #"^jdbc:sqlite:" "")))

(defonce server
  (let [s (db-server/start! {:port 0 :db-path (dbname-of (:db config/config))})]
    ;; The suite has no global fixture to hang a teardown on, and the runner
    ;; exits the JVM when it is done, so this is where the server is stopped.
    ;; `stop!` closes jetty before it rolls back what is still open, which is
    ;; the ordering that matters when the database outlives the server -- an
    ;; in-memory one does, for as long as the anchor connection holds it.
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (db-server/stop! s))))
    s))

(def remote
  "The handle the app is given. Not the one the tests use on themselves."
  {:db-server/url (:url server)})

(defn app-config
  "The `config/config` the REST handlers are given while they are under test.

   Every suite that stands a handler up builds its config from here -- four
   places, one request helper per REST suite, where there used to be seven
   `{:db …}` literals. (Two spots in `mutations_test` still build config inline
   in a test body; they call only `GET /api/describe`, which reaches no
   database, and the reasoning for leaving them is in the step-3 handoff.)

   Seven literals were seven places a switch could be reverted without anything
   noticing, which is exactly what had happened: the REST half was quietly
   running local while `api.harness-wiring-test`, which only ever looked at the
   /ui half, went on passing.

   Consolidating them was not enough on its own, and it is worth being exact
   about why. As a `def` this was `{:db remote}` evaluated when the namespace
   loaded, and the wiring test asserted about the definition. Reverting all
   seven literals therefore *still* left the suite green -- one place to look
   at, and nothing that failed if a suite looked elsewhere. A convention, not
   a guard.

   So it is a function, and it reads `remote` when it is **called**. That is
   what lets `api.harness-wiring-test` point the REST half at a dead port and
   watch each of the four helpers fail to reach a database -- the same redef
   that has always covered the /ui half now covers this one too, and a helper
   that had gone back to the local DataSource answers 200 instead, which is
   the failure.

   The one-argument arity carries whatever else a suite's handlers need in
   their config: `:folders` for the image routes, the role flags for the
   replica ones. A caller adds to the config rather than restating the handle."
  ([] (app-config nil))
  ([m] (merge {:db remote} m)))

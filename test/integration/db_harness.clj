(ns db-harness
  "The handle the integration suites hand to the application.

   ## What this was, and what is left of it

   It used to boot a whole db-server for the run, on an ephemeral port against
   the suite's own in-memory database, and hand the app a **remote** handle onto
   it. Two names onto one database: the tests kept using `config/config`'s
   DataSource for their own setup and assertions, while everything the app did
   went out over HTTP and came back. That arrangement existed so the suites
   could go end to end across the statement protocol without a line of any test
   body changing -- 88 statements across 19 files that would otherwise have had
   to be rewritten.

   The statement protocol retired in step 4 of the architecture rework: the hub
   answers calls about items, not statements, and the only process that runs a
   statement is the one holding the file. So there is nothing for a remote
   handle to be, and the two names collapse back into one.

   `app-config` stays, and is still a **function** rather than a def, for the
   reason it became one in step 3: a config read when a namespace loads is a
   convention, and one read when the handler is called is a guard. The four REST
   suites build their config through it, which is the arrangement that stopped
   seven `{:db …}` literals from drifting apart, and that is worth keeping
   whatever the handle turns out to be.

   The live hub/server pair is tested where it belongs now: `hub-proxy-test`
   stands both processes up for real, against two different databases, which is
   a sharper instrument than one database wearing two names ever was."
  (:require [clojure.string :as str]
            [et.rz.config :as config])
  (:import [org.sqlite SQLiteDataSource]))

(defn dbname-of
  "The SQLite dbname behind a datasource. `et.rz.hub.sqlite.connection` may hand back
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

(defn app-config
  "The `config/config` the REST handlers are given while they are under test.

   The one-argument arity carries whatever else a suite's handlers need in their
   config: `:folders` for the image routes, say. A caller adds to the config
   rather than restating the handle."
  ([] (app-config nil))
  ([m] (merge {:db (:db config/config)} m)))

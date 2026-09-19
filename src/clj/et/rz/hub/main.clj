(ns et.rz.hub.main
  "**The hub**: the one process that opens rhizome's SQLite file, and the only
   one that knows what the rows mean.

   It owns the file, the sqlite-vec extension, the schema, the embedder and the
   pollers, and it serves the two surfaces the application is made of -- `/ui`
   by command name and `/api` by path -- off a local DataSource. One instance,
   on one machine; every machine's `server` forwards to it.

   ## It used to be the opposite of that

   Until step 4 of the architecture rework this namespace spoke **statements**
   and nothing else: `/execute`, `/execute-one`, `/tx/begin|commit|rollback`,
   transit bodies, a token per held transaction and a sweeper for the abandoned
   ones. Its own docstring promised there would never be an endpoint here that
   mentioned an item.

   That promise is the thing the rework reversed, and for a measured reason: a
   dispatch call is 5 to 11 statements (`repository/fetch-context` is 9,
   `insert-item` is 11 inside one transaction), so a statement-level seam costs
   nine round trips for one navigation and holds SQLite's write lock across
   eleven. A call-level seam costs one. See
   `handoffs/RHIZOME_ARCH_REWORK_2.md`; the protocol is in the history.

   ## Boot

   `start!` with an opts map shaped like the `:db-server` config section, and
   `stop!` with what it returns. **`start!` reads no configuration** -- that is
   what lets a test boot as many of these as it likes on ephemeral ports -- so
   the file is read by `config-opts`, `seed-opts` and `-main`, and nowhere
   else."
  (:require et.rz.log-init ;; first: sets LOGS_DIR before any logging ns initialises logback
            [aero.core :as aero]
            [cambium.core :as log]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [compojure.core :refer [GET POST routes]]
            [et.rz.hub.sqlite.connection :as connection]
            [et.rz.hub.sqlite.schema :as schema]
            [et.rz.hub.dev-seed :as dev-seed]
            [et.rz.hub.repository.insertion.file :as file]
            [next.jdbc :as jdbc]
            [et.rz.placement :as placement]
            [et.rz.hub.poll :as poll]
            [et.rz.hub.rest-api :as et.rz.hub.rest-api]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.params :refer [wrap-params]]
            [et.rz.role :as role]
            [et.rz.hub.upload :as upload]
            [et.rz.hub.ui-api :as et.rz.hub.ui-api])
  (:import [org.eclipse.jetty.server ServerConnector]))

(def ^:private loopback
  "The only address this ever binds. Not an option: the routes below run
   arbitrary SQL with no authentication of any kind, so a `:host` argument
   would be a one-word way to publish the database to the network. The plan
   puts LAN exposure and the auth that has to come with it in a later step
   than this one."
  "127.0.0.1")

(defn- json-response
  [status body]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (json/generate-string body)})

;; -- routes ----------------------------------------------------------------

(defn- reach-the-database!
  "Run the smallest possible statement, to find out whether the database is
   actually there. Building a datasource proves nothing: SQLite opens lazily,
   and a read-only datasource in particular never creates or touches the file,
   so a db-server pointed at a path that does not exist comes up perfectly and
   fails on its first real statement."
  [{:keys [ds]}]
  (jdbc/execute-one! ds ["SELECT 1"]))

(defn health
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

(def ^:private reset-tables
  "Every table `/test/reset` empties. Relations first, so a foreign key can
   never hold a delete up."
  ["relations" "items" "history" "relation_history"
   "youtube_poll_channels" "youtube_poll_seen"
   "atom_poll_feeds" "atom_poll_seen"])

(defn- reset-database!
  "POST /test/reset -- empty the database. The e2e suite's globalSetup calls it,
   and it is the reason `check-e2e-db-path!` exists.

   It moved here from `server` (arch rework 2, step 4) because it is eight
   DELETEs and nothing else: the last caller of the statement protocol with no
   business speaking SQL over a wire. The hub owns the file, so the hub empties
   it.

   `:allow-reset?` comes from the config's top-level `:dev?`, so a production
   hub answers 403 exactly as `server` did. An explicit option rather than a
   config read, because `start!` reads no configuration -- see its docstring."
  [{:keys [ds allow-reset?]}]
  (if allow-reset?
    (do (doseq [t reset-tables]
          (jdbc/execute-one! ds [(str "DELETE FROM " t)]))
        {:status 200 :headers {"Content-Type" "text/plain"} :body "ok"})
    {:status 403 :headers {"Content-Type" "text/plain"} :body "not in dev mode"}))

(defn- upload!
  "POST /upload -- a dropped preview image, stored beside the row it belongs to.

   It moved here from `server` (arch rework 2, step 4) because an upload is two
   writes that have to agree: a file in the `:preview-images` folder and the
   item's resource-links in the database. Doing both on the machine that owns
   the database is the only arrangement where they land together.

   That means the bytes cross the wire, which is the opposite of what
   `/img-by-id` does -- see `server/upload-surface` for why that is the right
   way round for a write, and for the known property it leaves behind (the
   uploading machine sees the link before iCloud delivers it the file).

   A read-only hub refuses rather than letting its datasource throw."
  [{:keys [ds read-only?]} request]
  (if read-only?
    (do (log/warn {:event "replica-refusal" :uri "/upload"}
                  "read-only: refused /upload")
        (json-response 403 {:read-only-replica true}))
    (let [params (:multipart-params request)]
      (upload/upload-preview-file ds
                                  (get params "file")
                                  (get params "id")
                                  (get params "alternative-behaviour"))
      {:status 200 :headers {"Content-Type" "text/plain"} :body "File uploaded successfully!"})))

(defn- app
  [server]
  (routes
    (GET "/health" [] (health server))
    (POST "/test/reset" [] (reset-database! server))
    (wrap-multipart-params (POST "/upload" req (upload! server req)))
    ;; `/api/describe` is rhizome's own now. The statement protocol had a
    ;; describe of its own and, being registered first, answered that path --
    ;; documented as temporary when it was written, and this is the step it was
    ;; waiting for. What prober and any agent read there is the item API.
    ;;
    ;; The item surfaces. Same definitions `server` mounts, over this process's
    ;; own local DataSource -- which is the whole point: here a dispatch call
    ;; that costs nine statements costs nine *local* statements.
    (et.rz.hub.ui-api/ui-routes (constantly (:ds server))
                      {:intercept (fn [fn-name _req]
                                    (when (placement/machine-local-command? fn-name)
                                      (et.rz.hub.ui-api/refusal fn-name "machine-local-refusal"
                                                      (str "it is a machine-local command and has "
                                                           "to run where the files are"))))})
    (wrap-params (et.rz.hub.rest-api/rest-routes (constantly (:ds server))))
    (fn [req] (json-response 404 {:error (str "db-server: no such route: " (:uri req))}))))

;; -- process ---------------------------------------------------------------

(defn- check-vec-path!
  "Refuse to boot on a `:vec-path` this process cannot honour.

   `et.rz.hub.sqlite.connection` resolves the extension path once, at load, out of
   `:db-server :vec-path` in config.edn -- the same key `config-opts` reads
   below, so a server booted from the file agrees with it by construction and
   this check has nothing to say. What it is for is a caller that passes a
   *different* path explicitly: the extension is loaded on every connection
   this datasource hands out, and no option here can change that after the
   fact. Accepting the argument and silently ignoring it is the failure this
   whole seam is arranged to prevent, so it is refused instead."
  [vec-path]
  (when (and vec-path (not= vec-path connection/vec-extension-path))
    (throw (ex-info (str "db-server: :vec-path " (pr-str vec-path) " but et.rz.hub.sqlite.connection "
                         "loaded " (pr-str connection/vec-extension-path) " from :db-server "
                         ":vec-path in config.edn. The extension path is resolved once, at "
                         "load; a different one here could not take effect.")
                    {:vec-path vec-path :loaded connection/vec-extension-path}))))

(defn start!
  "Open the database and start serving on `:port`. Returns a server map; hand it
   back to `stop!`.

   - `:port`         the port to bind; 0 takes an ephemeral one, and the port
                     actually bound comes back as `:port` on the server map.
   - `:db-path`      the SQLite file. Required.
   - `:vec-path`     optional, and only checked -- see `check-vec-path!`.
   - `:read-only?`   open the database read-only. Schema application is skipped.
                     Nothing in production sets it since the replica machinery
                     retired -- the `primary.nosync` marker elects the hub now
                     rather than demoting it (see `-main`) -- but it is kept as
                     an option because the suites use it to exercise a database
                     that refuses writes at the driver.
   - `:allow-reset?` answer POST /test/reset rather than 403 it.

   There is no `:host`, and passing one is refused rather than ignored. This
   process binds loopback and the tunnel terminates on the far machine's
   loopback, which is what lets the item surfaces here need no authentication of
   their own; an option would be a way to publish them to the network by passing
   one argument, and silently dropping it would be a caller who thinks he has
   bound elsewhere and has not."
  [{:keys [port db-path vec-path read-only? allow-reset?] :as opts}]
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
                :allow-reset? (boolean allow-reset?)}]
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
    (let [jetty (jetty/run-jetty (app server) {:port port :host loopback :join? false})
          bound (.getLocalPort ^ServerConnector (first (.getConnectors jetty)))]
      (log/info {:port bound :db-path db-path :read-only? (boolean read-only?)}
                "db-server: up")
      (assoc server
        :jetty jetty
        :port  bound
        :url   (str "http://" loopback ":" bound)))))

(defn stop!
  "Stop a server `start!` returned.

   It used to roll back every transaction still open, and to stop jetty first so
   that no request could open one after the loop had passed -- a connection
   leaked with SQLite's write lock in its hand, invisible in a process that is
   exiting and a locked database in a test JVM that goes on running. That whole
   concern went with the statement protocol: transactions are no longer held
   across requests, because a request is now a call about items and a
   transaction lives and dies inside one."
  [{:keys [jetty]}]
  (when jetty (.stop jetty))
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
  ;; A missing :db-path is `start!`'s to refuse, in its own words. Reaching
  ;; `getCanonicalPath` with nil here turned that clean message into an NPE.
  (when (and (not (str/blank? (str db-path)))
             (= "1" (System/getProperty "rhizome.e2e")))
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
     {:port         (or (:port section) default-port)
      :db-path      (:db-path section)
      :vec-path     (:vec-path section)
      ;; The same gate `server` applied to /test/reset, read from the same flag:
      ;; dev answers it, production refuses it.
      :allow-reset? (boolean (:dev? c))})))

(defn check-elected!
  "Refuse to boot a hub on a machine that was not elected to run one.

   `primary.nosync` used to mean *may this instance write* -- present, and the
   database opened writable; absent, and it opened read-only, which is what made
   a synced copy a safe read-only replica. There are no replicas any more (one
   hub, one mode), so the marker was free, and it was given the job the
   deployment actually has: **which machine runs the hub.** It elects now
   instead of demoting.

   The cost of getting this wrong is worse than it sounds, and worse than it was.
   The database is `rhizome.db.nosync`, and `.nosync` is precisely the suffix
   that keeps iCloud from syncing it. So two hubs are not two writers racing over
   one file -- they are **two separate databases diverging in silence**, found
   whenever the owner next notices something missing. A refusal to boot is cheap
   against that.

   Dev is exempt, as it is everywhere else this marker is read: no checkout has
   one (`primary.nosync` is gitignored), so requiring it would mean no developer
   could ever start a hub.

   This catches a hub that *starts* unelected. It does nothing about a hub that
   is already running on a machine being demoted -- see the README's run
   section: demoting a machine means stopping its hub, not just removing the
   marker."
  ([] (check-elected! config-path))
  ([path]
   (let [c (aero/read-config path)]
     (when-not (or (:dev? c) (role/primary-marker-present?))
       (throw (ex-info (str "db-server: refusing to start. This machine has no "
                            role/primary-marker " beside its config.edn, so it was not "
                            "elected to hold the database. Exactly one machine runs the "
                            "hub; the others run a server that forwards to it. If this "
                            "machine is meant to take over, stop the hub on the old one "
                            "FIRST, move the database file across, then `touch "
                            role/primary-marker "` here.")
                       {:config-path path :marker role/primary-marker}))))))

(defn seed-opts
  "What `dev-seed/maybe-seed!` needs, from the same config.edn.

   Seeding moved here from `server` (arch rework 2, step 4) for the reason the
   pollers did: it is a write of items into the database, and the process that
   owns the database is the one that should make it. It was the largest block of
   SQL still crossing the wire.

   It re-reads the file rather than riding along on `config-opts`, which returns
   exactly what `start!` takes and is asserted as such. Two reads of a small
   file at boot is a cheaper price than a map that is two things at once.

   `:e2e?` is read off the sysprop, not the file: nothing writes `:e2e? true`
   into a config.edn -- `config.clj` forces it from `-Drhizome.e2e=1`, and
   `check-e2e-db-path!` already reads it the same way."
  ([] (seed-opts config-path))
  ([path]
   (let [c (aero/read-config path)]
     {:dev?       (boolean (:dev? c))
      :e2e?       (= "1" (System/getProperty "rhizome.e2e"))
      :skip-seed? (boolean (:skip-seed? c))})))

(defn poll-scheduling-enabled?
  "Whether this process should run the feed pollers.

   The pollers moved here from `server` (arch rework 2, step 3) for one reason:
   they must run **exactly once**, and there is exactly one hub. A poller on
   every machine's `server` would read the same feeds two or three times and
   race to insert the same items; a poller on the hub runs where the writes
   land and where the machine is always on.

   `server` asks the complementary question -- it schedules only when there is
   no hub to do it (`server/poll-scheduling-enabled?`), which is the
   single-process case. The two are pinned against each other in
   `poller-placement-test`: never both, because that is duplicate polling, and
   never neither in the world where feeds are supposed to be read.

   Two worlds are refused here, and both were `server`'s rules before:

   - **read-only** -- a replica hub does not own the file, so a poller could
     only fail on every tick. It must not exist rather than fail quietly.
   - **e2e** -- detected the same way `check-e2e-db-path!` does it, off
     `-Drhizome.e2e=1`. An e2e run gets a fixed database and asserts about its
     contents; a scheduler reaching youtube 30 seconds in would make the suite
     flaky for a reason nobody would find quickly."
  [server]
  (and (not (:read-only? server))
       (not= "1" (System/getProperty "rhizome.e2e"))))

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
  (check-elected!)
  (let [server (start! (config-opts))
        ds     (:ds server)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (poll/stop-scheduler!) (stop! server))))
    ;; Seeding and the file-context gate, both moved from `server` (step 4).
    ;; They run here rather than in `start!` for the same reason the pollers do:
    ;; the suites boot many servers inside one JVM, and `start!` is the server
    ;; while `-main` is the process. A read-only hub writes nothing, as before.
    (when-not (:read-only? server)
      ;; ImageMagick, gated here because this is where preview downscaling
      ;; happens now (see `upload!`). `server` keeps the same gate for the
      ;; single-process case, and the two are complementary the way the poller
      ;; predicates are.
      (upload/ensure-convert!)
      (let [{:keys [dev? e2e? skip-seed?] :as seed} (seed-opts)]
        (dev-seed/maybe-seed! (assoc seed :db ds))
        ;; A missing file-type context silently drops files of that type on
        ;; import, so refuse to come up without every named id. Exempt on an
        ;; empty database: e2e runs against one by design, and a fresh db was
        ;; just seeded above unless seeding was deliberately skipped.
        (when-not (or e2e? (dev-seed/items-empty? ds))
          (file/ensure-contexts! ds))
        (when (and dev? skip-seed?)
          (log/info "db-server: :skip-seed? is set, so nothing was seeded"))))
    (when (poll-scheduling-enabled? server)
      (poll/start-scheduler! ds))
    (log/info {:url (:url server)}
              (str "db-server: listening on " (:url server) " -- that is the url the "
                   "app-server derives, and nothing off this machine can reach it."))
    nil))

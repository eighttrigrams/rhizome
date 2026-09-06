(ns server
  (:require log-init ;; first: sets LOGS_DIR before any logging ns initialises logback
            [ring.adapter.jetty :as j]
            upload
            [clojure.string :as str]
            [compojure.core :refer [context GET POST PUT]]
            [ring.util.codec :as codec]
            [ring.util.response :as response]
            [ring.middleware.json :as json]
            [env :refer [wrap-env-defaults]]
            [config :as config]
            [dev-seed :as dev-seed]
            [db :as db]
            [repository :as r]
            [repository.insertion.file :as file]
            [poll :as poll]
            [replica :as replica]
            [et.vp.ds :as datastore]
            opener
            dispatch
            rest-api
            [cambium.core :as log]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.java.io :as io])
  (:gen-class))

(defn api-handler [{{msg :msg} :body}] (tap> [:resources (r/list-resources)]) {:body {:echo msg}})

(defn- open [{{:keys [file-id]} :route-params}] (opener/open file-id) {:status 200})

(defn- api
  []
  (fn [req]
    (if (and (not (:dev? config/config))
             (or (not (= (:private-addr config/config) (:remote-addr req)))
                 (not (= (:private-user-agent config/config)
                         (get-in req [:headers "user-agent"])))))
      (do (log/warn (pr-str req)) {:status 403})
      ((context ""
                []
                (-> #(response/response (log/with-logging-context
                                          {:context :request}
                                          (dispatch/handler (-> %
                                                                (assoc-in [:body :server-args :db]
                                                                          (:db config/config))))))
                    json/wrap-json-response
                    (json/wrap-json-body {:keywords? true})))
        req))))

(defn upload-handler
  "POST /upload — store a dropped preview image. A write, so a read-only replica
  refuses it gracefully instead of letting the read-only datasource throw."
  [request]
  (if (replica/read-only?)
    (do (log/warn {:event "replica-refusal" :uri "/upload"}
                  "read-only replica: refused /upload")
        (replica/refusal-response))
    (let [uploaded-file (get (-> request
                                 :multipart-params)
                             "file")
          id (get (-> request
                      :multipart-params)
                  "id")
          alternative-behaviour? (get (-> request
                                          :multipart-params)
                                      "alternative-behaviour")]
      (upload/upload-preview-file (:db config/config) uploaded-file id alternative-behaviour?)
      ;; Process the uploaded file here. For example, save it to a directory.
      (response/response "File uploaded successfully!"))))

;; The directories configured under :folders are served beneath the /imgs URL
;; prefix by wrap-imgs (used in both dev and prod): :images backs /imgs/*
;; (tracked originals) and :preview-images backs /imgs/Preview/* (generated
;; previews), so no symlinks are needed. In prod both are validated to exist at
;; config load time; in dev they are hardcoded under ./files/ (see config).
(def ^:private images-folder
  (-> config/config :folders :images))

(def ^:private preview-images-folder
  (-> config/config :folders :preview-images))

(defn- wrap-imgs
  "Serve files under the /imgs/* URL prefix from the filesystem: /imgs/Preview/*
  from preview-images-folder, everything else under /imgs/* from images-folder.
  Ring's :uri is still percent-encoded, so the path is url-decoded before the
  file lookup -- without this, filenames containing spaces 404. file-response's
  :root guards against directory traversal (also for decoded ..)."
  [handler images-folder preview-images-folder]
  (fn [req]
    (let [uri (:uri req)]
      (cond
        (str/starts-with? uri "/imgs/Preview/")
        (or (response/file-response (codec/url-decode (subs uri (count "/imgs/Preview"))) {:root preview-images-folder})
            {:status 404 :body "Not Found"})

        (str/starts-with? uri "/imgs/")
        (or (response/file-response (codec/url-decode (subs uri (count "/imgs"))) {:root images-folder})
            {:status 404 :body "Not Found"})

        :else (handler req)))))

(defn- img-by-id-handler
  [{{:keys [item-id]} :route-params}]
  (try (let [item (datastore/get-item (:db config/config) {:id item-id})
             data (:data item)
             title (:title item)
             resource-links (:resource-links data)]
         (cond (:image resource-links)
                 (let [file (io/file images-folder (:image resource-links))]
                   (if (.exists file)
                     (response/file-response (str file))
                     {:status 404 :body "Image file not found"}))
               (and title (re-matches #".*\.(png|jpg|jpeg|PNG|JPG|JPEG)$" title))
                 (let [file (io/file images-folder title)]
                   (if (.exists file)
                     (response/file-response (str file))
                     {:status 404 :body "Image file not found"}))
               :else {:status 404 :body "Item has no image"}))
       (catch Exception e
         (log/error e "Error serving image by ID")
         {:status 500 :body "Internal server error"})))

(defn- reset-handler
  [_req]
  (if (true? (:dev? config/config))
    (let [db (:db config/config)]
      (db/execute-one! db ["DELETE FROM relations"])
      (db/execute-one! db ["DELETE FROM items"])
      (db/execute-one! db ["DELETE FROM history"])
      (db/execute-one! db ["DELETE FROM relation_history"])
      (db/execute-one! db ["DELETE FROM youtube_poll_channels"])
      (db/execute-one! db ["DELETE FROM youtube_poll_seen"])
      (db/execute-one! db ["DELETE FROM atom_poll_feeds"])
      (db/execute-one! db ["DELETE FROM atom_poll_seen"])
      {:status 200 :body "ok"})
    {:status 403 :body "not in dev mode"}))

(defn- routes
  []
  (context
    "/"
    []
    (context "/ui" [] (POST "/" [] (api)))
    (rest-api/rest-routes)
    (POST "/test/reset" [] reset-handler)
    (GET "/open/:file-id" [] open)
    (GET "/img-by-id/:item-id" [] img-by-id-handler)
    (POST "/upload" req (upload-handler req))
    (GET "/" [] (response/resource-response "public/index.html"))
    (fn [req] (log/warn (str "File not found:" (:uri req))) {:status 404 :body "Not Found"})))

(defn app
  []
  (-> (routes)
      wrap-env-defaults
      (wrap-resource "public")
      (wrap-imgs images-folder preview-images-folder)
      wrap-params
      wrap-multipart-params))

(defn check-folders-exist!
  "Startup filesystem gate, run once logging is configured (config load itself
   must stay logging-dependency-free -- see log-init -- so this lives here, not
   in config). Walks every configured folder and:
   - logs a warn for each one that is missing on disk;
   - additionally refuses to start (error + throw) when :preview-images is
     missing -- previews are written by uploads and scrapers and back
     /imgs/Preview/*, so the app cannot function without it.
   The other folders (:imports :audio :video :docs :images) are soft: a missing
   one only disables the matching slice of import/deletion at runtime (each of
   those paths logs its own warn), and may just be a temporarily-unmounted
   drive, so we warn and carry on."
  []
  (doseq [k config/folder-keys]
    (let [dir (get-in config/config [:folders k])]
      (when-not (.isDirectory (io/file dir))
        (log/warn (str "Configured folder " k " does not exist: " dir))
        (when (= k :preview-images)
          (let [msg (str "Refusing to start: :preview-images folder does not exist: " dir)]
            (log/error msg)
            (throw (ex-info msg {:folder dir}))))))))

(defn- log-instance-role!
  "One unmissable startup line naming the role this process booted with, and why.
  Prod only -- dev mode has no primary/replica distinction (see
  config/read-only-replica?)."
  []
  (when-not (:dev? config/config)
    (let [dir (System/getProperty "user.dir")]
      (if (replica/read-only?)
        (log/warn {:instance-role "read-only-replica"}
                  (str "INSTANCE ROLE: READ-ONLY REPLICA -- prod mode and no "
                       config/primary-marker " in " dir
                       ": db opened read-only, every write refused, pollers not scheduled"))
        (log/info {:instance-role "primary"}
                  (str "INSTANCE ROLE: PRIMARY -- " config/primary-marker
                       " present in " dir ": writes enabled"))))))

(defn poll-scheduling-enabled?
  "The youtube/atom pollers write (imported items plus the seen tables), so they
  are only scheduled where writing is possible: never under e2e, and never on a
  read-only replica -- there the job must not exist at all rather than fail on
  every tick."
  []
  (and (not (:e2e? config/config))
       (not (replica/read-only?))))

(defn- start-pollers!
  []
  (when (poll-scheduling-enabled?)
    (poll/start-scheduler! (:db config/config))))

(defn- prepare-for-writing!
  "The startup steps that only make sense where this instance may write, skipped
  wholesale on a read-only replica:
  - the seed write. A replica's db is owned by the primary and arrives through
    the sync already seeded; its datasource would refuse the write anyway.
  - `ensure-convert!` gates startup on ImageMagick, which only preview
    downscaling needs. A replica can never convert anything -- /upload is
    refused and the pollers are not scheduled -- so gating it there would refuse
    to boot over a capability it cannot use.

  **The schema is not applied here any more.** The db-server owns the file and
  applies it as it opens it, before this process is started at all -- and on a
  replica it skips it there for the same reason it was skipped here. The seed
  stays: it is a write of *items*, which is app-side business, and it travels
  over the wire like every other statement."
  []
  (when-not (replica/read-only?)
    (upload/ensure-convert!)
    (dev-seed/maybe-seed! {:db         (:db config/config)
                           :dev?       (:dev? config/config)
                           :e2e?       (:e2e? config/config)
                           :skip-seed? (:skip-seed? config/config)})))

(defn- app-role-reason
  "What this process concluded about its own role, and *why*, in the words the
   reader needs to check it.

   Three cases, not two. An app-server is a primary either because it is in dev
   mode -- which is never a replica, marker or no marker -- or because it is in
   prod mode WITH the marker; only prod-without-marker is the replica. Saying
   \"prod mode and <marker> in <dir>\" for every primary was false in exactly the
   scenario the refusal below exists for: a dev app-server in front of a
   standalone db-server, where it denied the mode the reader is in and sent them
   hunting a marker file that is not there.

   `log-instance-role!` above has always had this right, and says why in one
   line: dev mode has no primary/replica distinction."
  []
  (let [dir (System/getProperty "user.dir")]
    (cond
      (:dev? config/config)
      (str "primary -- dev mode (:dev? true in " dir "/config.edn), which is never a "
           "replica: no marker is consulted and none would change it")

      (config/primary-marker-present?)
      (str "primary -- prod mode and " config/primary-marker " in " dir)

      :else
      (str "read-only replica -- prod mode and no " config/primary-marker " in " dir))))

(defn- check-db-server-role!
  "**The two processes decide primary-vs-replica independently, so the startup
  check is where they are made to agree.**

  Same rule, `role`'s, and the same marker file -- but each reads it in ITS OWN
  working directory, and only the marker is shared by construction. `:dev?` is
  per-file, so a db-server started against a config.edn of its own (the
  standalone `{:db-server {…}}` arrangement the plan allows, and the one the
  later remote-machine step is built on) reads no `:dev?`, calls itself prod,
  finds no marker beside itself, and opens the database READ-ONLY -- while this
  process, reading the dev config.edn it was started with, believes it is a
  primary and may write.

  Nothing about that is visible until the first write: `SELECT 1` succeeds
  perfectly well against a read-only database, `/api/status` reports
  `read-only-replica: false`, and the failure arrives much later as a bare
  SQLITE_READONLY with nothing to connect it to a marker file in another
  directory. Which is precisely the drift `role` exists to prevent, and the
  reason a shared *rule* is not the same thing as a shared *verdict*.

  So the verdicts are compared, and any disagreement is a refusal -- in both
  directions. A db-server that is writable under an app that refuses every write
  is the milder half, but it is the same confusion and the same fix."
  [handle]
  (let [db-read-only?  (db/remote-read-only? handle)
        app-read-only? (replica/read-only?)]
    (when-not (= db-read-only? app-read-only?)
      (let [msg (str "Refusing to start: this app-server and its db-server disagree about "
                     "whether this instance may write.\n"
                     "  app-server: " (app-role-reason) "\n"
                     "  db-server:  " (if db-read-only? "read-only" "writable")
                     " -- it read its own config.edn and looked for "
                     config/primary-marker " in the directory IT was started in ("
                     (:db-server/url handle) ")\n"
                     (if (:dev? config/config)
                       ;; The flagship case, and the one where naming the remedy
                       ;; is worth more than restating the rule: a db-server
                       ;; handed a config.edn of its own sees no `:dev?` in it,
                       ;; concludes prod, finds no marker beside itself, and opens
                       ;; the database read-only.
                       (str "A db-server reading a config.edn of its own sees no :dev? in it, "
                            "so it concludes prod, looks for " config/primary-marker
                            " beside itself, does not find one, and opens the database "
                            "read-only. Add :dev? true to that file, or start both processes "
                            "from this directory so they read this config.edn.")
                       (str "Both decide this from the same rule and the same marker file, but "
                            "each in its own working directory. Start them from the same one, "
                            "or give the db-server a config.edn that says what this one "
                            "says.")))]
        (log/error msg)
        (throw (ex-info msg {:db-server/url        (:db-server/url handle)
                             :db-server/read-only? db-read-only?
                             :app/read-only?       app-read-only?}))))
    (log/info {:db-server (:db-server/url handle) :read-only? db-read-only?}
              (str "app-server: database reached over " (:db-server/url handle)
                   ", and both processes call this instance "
                   (if db-read-only? "a read-only replica" "a primary")))))

(defn- check-db-server!
  "One statement, before anything else needs one, so that a db-server that is
  not there says so in one line instead of surfacing as a connection refused in
  the middle of seeding. Then `check-db-server-role!`, which is the other half
  and the less obvious one.

  It is not a health check with a retry loop: waiting for the db-server is
  `scripts/start.sh`'s job, which polls `/health` before it starts this process
  at all. This is the message for when that did not happen -- an app-server
  started by hand, or pointed at the wrong port."
  []
  (let [handle (:db config/config)]
    (when (db/remote? handle)
      (try (db/execute-one! handle ["SELECT 1"])
           (catch Throwable t
             (let [msg (str "Refusing to start: no db-server answering at "
                            (:db-server/url handle) " (" (.getMessage t) "). "
                            "Start it first -- 'make start' does, and 'make start-db' "
                            "runs it alone.")]
               (log/error t msg)
               (throw (ex-info msg {:db-server/url (:db-server/url handle)} t)))))
      (check-db-server-role! handle))))

(defn start-http-server!
  []
  (when (and (not (:dev? config/config))
             (or (nil? (:private-addr config/config))
                 (not (string? (:private-addr config/config)))))
    (throw (Exception. "config invalid")))
  (log-instance-role!)
  (check-db-server!)
  (prepare-for-writing!)
  ;; A missing file-type context silently drops files of that type on import,
  ;; so refuse to come up unless every named id is present. The only exemption
  ;; is a completely empty db: e2e runs against one by design, and a fresh
  ;; prod/dev db was just seeded above (so it won't read as empty here unless
  ;; seeding was deliberately skipped).
  (when-not (or (:e2e? config/config)
                (dev-seed/items-empty? (:db config/config)))
    (file/ensure-contexts! (:db config/config)))
  ;; Filesystem gate: warn on any missing folder, refuse to start without
  ;; :preview-images. Skipped under e2e -- it runs in CI where the gitignored
  ;; ./files/* dev folders don't exist (same exemption ensure-contexts! uses).
  (when-not (:e2e? config/config)
    (check-folders-exist!))
  (let [host (or (:bind-host config/config)
                 (if (:dev? config/config) "0.0.0.0" "127.0.0.1"))]
    (start-pollers!)
    (future (j/run-jetty (app) {:port (:port config/config)
                                :host host}))))

(defn -main
  [& _args]
  (deref (start-http-server!)))
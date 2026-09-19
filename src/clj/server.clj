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
            [db :as db]
            [repository :as r]
            [poll :as poll]
            [replica :as replica]
            [et.vp.ds :as datastore]
            opener
            dispatch
            rest-api
            [ui-api :as ui-api]
            [hub-api :as hub-api]
            [hub-proxy :as hub-proxy]
            [placement :as placement]
            [cheshire.core :as cheshire]
            [cambium.core :as log]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.java.io :as io])
  (:gen-class))

(defn api-handler [{{msg :msg} :body}] (tap> [:resources (r/list-resources)]) {:body {:echo msg}})

(defn- open [{{:keys [file-id]} :route-params}] (opener/open file-id) {:status 200})

(defn- ui-intercept
  "Where a `/ui` command runs, decided per call from `placement`.

   The whole thing is conditional on there being a hub to talk to. Without one
   this process holds the database itself -- test mode, e2e, a single-machine
   dev session with no db-server -- and every command is answered here exactly
   as it always was. That is what keeps this change from having two meanings.

   With a hub:

   - a **machine-local** command is answered here: it works on this disk, and
     this is the machine the human is sitting at;
   - anything else is **forwarded whole** to the hub, which answers it off the
     database directly. This is the round trip the rework exists for: one hop
     for the call instead of nine for its statements;
   - an **unclassified** command is refused. It cannot be forwarded, because
     nobody has said whether it touches this disk, and guessing is how a file
     operation ends up running on the wrong machine in silence. The sweep test
     makes this unreachable in a release; it is here for the release where it
     is not.

   The envelope is rebuilt rather than replayed: the JSON body was already
   consumed to read `:fn`, and `{:fn :args}` is the whole of it."
  [fn-name req]
  (when-let [url (hub-proxy/hub-url)]
    (cond
      (placement/machine-local-command? fn-name) nil

      (placement/classified? fn-name)
      (hub-proxy/forward url
                         (assoc req
                           :headers {"content-type" "application/json"
                                     "accept"       "application/json"}
                           :body (java.io.ByteArrayInputStream.
                                   (.getBytes ^String (cheshire/generate-string
                                                        {:fn   fn-name
                                                         :args (get-in req [:body :args])})
                                              "UTF-8"))))

      :else
      (ui-api/refusal fn-name "unclassified-command"
                      (str "it has not been placed on either side of the hub/server "
                           "split -- see the placement namespace, and the sweep test "
                           "that should have caught this before it shipped")))))

(defn- api
  "The browser gate in front of `/ui`. The handler itself now lives in `ui-api`,
   because the hub answers the same commands off its own DataSource and one
   definition beats two that agree to match (arch rework 2, step 2). What stays
   here is the part that is about *this* surface: only the local browser may
   POST to it outside dev mode."
  []
  (let [h (ui-api/handler #(:db config/config) {:intercept ui-intercept})]
    (fn [req]
      (if (and (not (:dev? config/config))
               (or (not (= (:private-addr config/config) (:remote-addr req)))
                   (not (= (:private-user-agent config/config)
                           (get-in req [:headers "user-agent"])))))
        (do (log/warn (pr-str req)) {:status 403})
        (h req)))))

(defn upload-handler
  "POST /upload — store a dropped preview image. A write, so a read-only replica
  refuses it gracefully instead of letting the read-only datasource throw.

  This is the **local** branch, used when this process holds the database
  itself. With a hub, `upload-surface` forwards the whole multipart request
  there instead: see its docstring for why the bytes travel rather than the
  file staying here."
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

(defn- upload-surface
  "POST /upload, forwarded whole to the hub when there is one.

  **The bytes travel and the file does not stay here**, which is the opposite
  of what `/img-by-id` does, and deliberately. An upload is a write: it puts a
  preview file on disk *and* writes the item's resource-links, and those two
  have to agree. Sending the bytes to the machine that owns the database is the
  only arrangement where they land together -- the alternative needs a new
  `/api` route, the reason-required write gate in front of it, and a window
  where one half is written and the other is not. Uploads are rare, human, and
  not on a read path, so one round trip with a file in it is a fair price for a
  seam that does not exist.

  **A known property, not a free win:** the machine that performed the upload
  sees the resource-link before the synced folder delivers it the file. The
  data goes through the tunnel and the file comes back through iCloud, so on a
  remote machine the preview appears late by however long the sync takes. On
  the hub itself that window is zero, which is today's only case.

  Multipart is parsed here rather than around the whole app, which is what
  makes the forward possible at all: `wrap-multipart-params` consumes the body,
  so a global one would leave nothing to forward."
  []
  ;; Through the var, not the value: the middleware is built once and the
  ;; handler is looked up per request, which is what lets a test redefine it to
  ;; prove it was never reached.
  (let [local (wrap-multipart-params #'upload-handler)]
    (fn [req]
      (if-let [url (hub-proxy/hub-url)]
        (hub-proxy/forward url req)
        (local req)))))

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

(defn- serve-image-named
  "Serve `filename` out of this machine's images folder, or 404.

   The canonical path is checked to fall under the folder because the name comes
   from the database and is free-form text -- whatever the file was called on
   import. The old version of this handler read straight at the filesystem
   without that check, and `rest-api.queries/image-file` said so in its
   docstring in as many words: 'not a model to copy'. It is copied now."
  [filename]
  (let [root (io/file images-folder)
        f    (io/file root filename)]
    (if (and (str/starts-with? (.getCanonicalPath f) (.getCanonicalPath root))
             (.isFile f))
      (response/file-response (str f))
      {:status 404 :body "Image file not found"})))

(defn- img-by-id-handler
  "GET /img-by-id/:item-id -- the item's image, off this machine's disk.

   The row lives on the hub and the file lives here, so this asks the hub which
   file and then serves it itself (see `hub-api`): the question crosses the
   tunnel, the bytes do not.

   The manifest is asked first, and both of its lists are read. An entry under
   `:missing` means the *hub* could not resolve the file -- on a machine whose
   sync has delivered it that is not the same answer, and the filename is all
   this needs from either list.

   The second branch is for older rows that declare no image at all but whose
   title *is* an image filename. It costs a second call, so it is only made when
   the manifest came back with nothing."
  [{{:keys [item-id]} :route-params}]
  (try
    (let [manifest (hub-api/item-images item-id)
          declared (->> (concat (:images manifest) (:missing manifest))
                        (filter #(= "image" (:kind %)))
                        first)]
      (cond
        declared (serve-image-named (:filename declared))

        (nil? manifest) {:status 404 :body "Item not found"}

        :else
        (let [title (:title (hub-api/item item-id))]
          (if (and title (re-matches #".*\.(png|jpg|jpeg|PNG|JPG|JPEG)$" title))
            (serve-image-named title)
            {:status 404 :body "Item has no image"}))))
    (catch Exception e
      (log/error e "Error serving image by ID")
      {:status 500 :body "Internal server error"})))

(defn- reset-handler
  "POST /test/reset. Forwarded to the hub when there is one -- it owns the file,
   and emptying it is eight DELETEs that have no business crossing a wire as
   SQL (arch rework 2, step 4). The local branch below is what a single-process
   run still does, and it goes when the statement protocol does."
  [req]
  (if-let [url (hub-proxy/hub-url)]
    (hub-proxy/forward url req)
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
      {:status 403 :body "not in dev mode"})))

(defn- rest-surface
  "`/api`, answered by the hub when there is one and by this process when there
   is not (see `hub-proxy`).

   `/api/describe` used to be excluded, because the hub answered that path with
   the *statement protocol's* description and forwarding it would have handed
   agents the wrong document. The protocol retired in step 4 and the path
   changed hands with it, so the whole surface forwards -- which is what makes
   `/api/describe` through this machine describe the API this machine actually
   serves."
  []
  (let [local (rest-api/rest-routes)]
    (fn [req]
      (if-let [url (and (str/starts-with? (:uri req) "/api")
                        (hub-proxy/hub-url))]
        (hub-proxy/forward url req)
        (local req)))))

(defn- routes
  []
  (context
    "/"
    []
    (context "/ui" [] (POST "/" [] (api)))
    (rest-surface)
    (POST "/test/reset" [] reset-handler)
    (GET "/open/:file-id" [] open)
    (GET "/img-by-id/:item-id" [] img-by-id-handler)
    (POST "/upload" [] (upload-surface))
    (GET "/" [] (response/resource-response "public/index.html"))
    (fn [req] (log/warn (str "File not found:" (:uri req))) {:status 404 :body "Not Found"})))

(defn app
  []
  (-> (routes)
      wrap-env-defaults
      (wrap-resource "public")
      (wrap-imgs images-folder preview-images-folder)
      wrap-params))

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
       (not (replica/read-only?))
       ;; And not when there is a hub: it runs them, over the database it owns
       ;; (`db-server/poll-scheduling-enabled?`). Two machines each running a
       ;; `server` would otherwise read every feed twice and race to insert the
       ;; same items. This is the half of the pair that says "not me"; they are
       ;; pinned against each other in poller-placement-test.
       (nil? (hub-proxy/hub-url))))

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

  **Neither the schema nor the seed is applied here any more.** The hub owns
  the file: it applies the schema as it opens it, and since step 4 it seeds it
  too, from its own `-main`. What is left here is the one thing that is about
  *this machine* rather than about the database -- ImageMagick, which only
  preview downscaling needs and which has to exist where the downscaling
  happens."
  []
  (when (and (not (replica/read-only?))
             ;; Only when this process will do the downscaling itself. With a
             ;; hub, /upload is forwarded and ImageMagick is needed there, not
             ;; here -- the hub gates on it in its own -main. Complementary, the
             ;; way the poller predicates are.
             (nil? (hub-proxy/hub-url)))
    (upload/ensure-convert!)))

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
  [url health]
  (let [db-read-only?  (boolean (:read-only? health))
        app-read-only? (replica/read-only?)]
    (when-not (= db-read-only? app-read-only?)
      (let [msg (str "Refusing to start: this app-server and its db-server disagree about "
                     "whether this instance may write.\n"
                     "  app-server: " (app-role-reason) "\n"
                     "  db-server:  " (if db-read-only? "read-only" "writable")
                     " -- it read its own config.edn and looked for "
                     config/primary-marker " in the directory IT was started in ("
                     url ")\n"
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
        (throw (ex-info msg {:db-server/url        url
                             :db-server/read-only? db-read-only?
                             :app/read-only?       app-read-only?}))))
    (log/info {:db-server url :read-only? db-read-only?}
              (str "app-server: database reached over " url
                   ", and both processes call this instance "
                   (if db-read-only? "a read-only replica" "a primary")))))

(defn- check-db-server!
  "One `/health` call, before anything else needs the hub, so that a hub that is
  not there says so in one line instead of surfacing as a connection refused in
  the middle of the first request. Its answer is then handed to
  `check-db-server-role!`, which is the other half and the less obvious one --
  one call answering both questions, since `/health` reports the role too.

  It used to be `SELECT 1` over the statement protocol. `/health` says the same
  thing -- something is listening and it has a database open -- without this
  process needing to speak SQL to ask, which is step 4's whole direction.

  It is not a health check with a retry loop: waiting for the db-server is
  `scripts/start.sh`'s job, which polls `/health` before it starts this process
  at all. This is the message for when that did not happen -- an app-server
  started by hand, or pointed at the wrong port."
  []
  (when-let [url (hub-proxy/hub-url)]
    (let [health (try (hub-proxy/health url)
                      (catch Throwable t
                        (let [msg (str "Refusing to start: no db-server answering at "
                                       url " (" (.getMessage t) "). "
                                       "Start it first -- 'make start' does, and "
                                       "'make start-db' runs it alone.")]
                          (log/error t msg)
                          (throw (ex-info msg {:db-server/url url} t)))))]
      (check-db-server-role! url health))))

(defn start-http-server!
  []
  (when (and (not (:dev? config/config))
             (or (nil? (:private-addr config/config))
                 (not (string? (:private-addr config/config)))))
    (throw (Exception. "config invalid")))
  (log-instance-role!)
  (check-db-server!)
  (prepare-for-writing!)
  ;; The file-type context gate moved to the hub with the seed it depends on
  ;; (db-server/-main): both are questions about the contents of the database,
  ;; and the process that owns the file is the one that can answer them without
  ;; a wire.
  ;;
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
(ns et.rz.server.main
  (:require et.rz.log-init ;; first: sets LOGS_DIR before any logging ns initialises logback
            [ring.adapter.jetty :as j]
            [et.rz.hub.upload :as upload]
            [clojure.string :as str]
            [compojure.core :refer [context GET POST PUT]]
            [ring.util.codec :as codec]
            [ring.util.response :as response]
            [ring.middleware.json :as json]
            [env :refer [wrap-env-defaults]]
            [et.rz.config :as config]
            [et.rz.hub.db :as db]
            [et.rz.hub.repository :as r]
            [et.rz.hub.poll :as poll]
            [et.rz.hub.ds :as datastore]
            [et.rz.server.opener :as opener]
            et.rz.hub.dispatch
            et.rz.hub.rest-api
            [et.rz.hub.ui-api :as et.rz.hub.ui-api]
            [et.rz.server.hub-api :as hub-api]
            [et.rz.server.hub-proxy :as hub-proxy]
            [et.rz.placement :as placement]
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
   dev session with no hub -- and every command is answered here exactly
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
      (et.rz.hub.ui-api/refusal fn-name "unclassified-command"
                      (str "it has not been placed on either side of the hub/server "
                           "split -- see the placement namespace, and the sweep test "
                           "that should have caught this before it shipped")))))

(defn- api
  "The browser gate in front of `/ui`. The handler itself now lives in `et.rz.hub.ui-api`,
   because the hub answers the same commands off its own DataSource and one
   definition beats two that agree to match (arch rework 2, step 2). What stays
   here is the part that is about *this* surface: only the local browser may
   POST to it outside dev mode."
  []
  (let [h (et.rz.hub.ui-api/handler #(:db config/config) {:intercept ui-intercept})]
    (fn [req]
      (if (and (not (:dev? config/config))
               (or (not (= (:private-addr config/config) (:remote-addr req)))
                   (not (= (:private-user-agent config/config)
                           (get-in req [:headers "user-agent"])))))
        (do (log/warn (pr-str req)) {:status 403})
        (h req)))))

(defn upload-handler
  "POST /upload — store a dropped preview image.

  This is the **local** branch, used when this process holds the database
  itself. With a hub, `upload-surface` forwards the whole multipart request
  there instead: see its docstring for why the bytes travel rather than the
  file staying here."
  [request]
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
    (response/response "File uploaded successfully!")))

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
   without that check, and `et.rz.hub.rest-api.queries/image-file` said so in its
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
  (let [local (et.rz.hub.rest-api/rest-routes)]
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

(defn poll-scheduling-enabled?
  "The youtube/atom pollers write (imported items plus the seen tables), so they
  are only scheduled where writing is possible: never under e2e, and never when
  there is a hub to do it."
  []
  (and (not (:e2e? config/config))
       ;; Not when there is a hub: it runs them, over the database it owns
       ;; (`et.rz.hub.main/poll-scheduling-enabled?`). Two machines each running a
       ;; `server` would otherwise read every feed twice and race to insert the
       ;; same items. This is the half of the pair that says "not me"; they are
       ;; pinned against each other in poller-placement-test.
       (nil? (hub-proxy/hub-url))))

(defn- start-pollers!
  []
  (when (poll-scheduling-enabled?)
    (poll/start-scheduler! (:db config/config))))

(defn- prepare-for-writing!
  "What is left of the startup steps that were about being able to write.

  The schema and the seed are the hub's -- it owns the file, applies the schema
  as it opens it, and seeds it from its own `-main`. What is left here is the
  one thing about *this machine* rather than about the database: ImageMagick,
  which preview downscaling needs and which has to exist where the downscaling
  happens.

  So it runs only when this process will do that downscaling itself. With a hub,
  /upload is forwarded and `convert` is needed there instead -- the hub gates on
  it in its own -main. Complementary, the way the poller predicates are."
  []
  (when (nil? (hub-proxy/hub-url))
    (upload/ensure-convert!)))

(defn hub-identity-problem
  "Is the hub that answered `/health` the one this machine should be talking
   to? nil when it is, or `{:problem kw :message s}` when it is not.

   ## The failure this exists for

   `:hub-url` is `http://127.0.0.1:3008` on **every** machine -- that is the
   property the ssh tunnel buys, and it is why one config.edn is correct
   everywhere. The cost of it is that \"the hub\" and \"a hub left running from
   a trip\" are the same address. Sequence, using only the README's own tooling:
   the laptop travels as the hub; the owner comes home, removes
   `primary.nosync`; `rhizome-start` skips starting a hub (no marker), finds
   `:3008/health` answering (the orphan answers it), and starts a `server` that
   reads and writes the **stale travel database** -- a complete, plausible
   rhizome with the mini's recent work missing, and nothing anywhere saying so.

   `check-elected!` cannot see this. It runs when a hub *starts*, and this hub
   started legitimately, days ago, on the machine that was then the hub.

   ## The rule, which needs no new configuration

   The marker already says which machine is supposed to hold the database, so
   the expectation is derivable rather than declarable:

   | `primary.nosync` here | the hub answering must be |
   | --- | --- |
   | present | **this machine** |
   | absent  | **not this machine** |

   Both directions are always true in production, because a hub only starts
   where the marker is (`et.rz.hub.main/check-elected!`). The second row is the
   sequence above. The first is its mirror -- a `server` on the mini whose
   `:hub-url` has been pointed somewhere else, or a stale tunnel holding local
   `:3008` in front of the hub that is right there.

   ## Why the hostname and not the db path

   The obvious field is the wrong one. `:db-path` is `./rhizome.db.nosync` in a
   config.edn that is identical on every machine, inside a deploy directory
   that is the same iCloud path on every machine; canonicalised, two machines'
   answers differ only if their usernames do. It is reported on `/health` to be
   *read*, and the hostname is what is *compared*. Both sides compute it through
   one function for that reason -- see `et.rz.role/hostname`.

   ## An unknown hostname is a refusal, not a pass

   nil means *could not tell*. Treating it as a value would make two machines'
   missing hostnames compare equal, which is precisely the wrong answer, so
   either side being blank is its own problem with its own message. An old hub
   that predates this check is the likeliest cause and the message says so.

   ## It is not the only line of defence, on purpose

   **The README requires a start script to refuse**, on a machine with no
   marker, when `:3008` is held by a process that is not `ssh` -- see *Package,
   deploy and run*, which states the requirement rather than showing an
   implementation, because the implementation is each operator's own and lives
   in his shell rather than in this repository. That catches the same failure
   **one layer earlier and differently**: before any process starts, by asking
   the operating system what is listening rather than asking the thing itself,
   so it needs no running hub and no `/health` at all. This catches it for a
   `server` started **any other way** -- by hand, from a make target, by a
   supervisor, or on the mini, where no such script is what is running. The two
   overlap and neither subsumes the other; deleting one because the other exists
   reopens the half it did not cover. `hub-identity-sweep-test` pins them to each
   other, the way `poller-placement-test` pins the poller predicates.

   **Only this half is testable from here**, since the other is a requirement on
   a script this repository does not contain: what the sweep test can still hold
   is that the README goes on asking for it, and that the two go on naming each
   other."
  [{:keys [elected? mine theirs url]}]
  (cond
    (str/blank? (str mine))
    {:problem :own-hostname-unknown
     :message (str "this machine could not determine its own hostname, so it cannot tell "
                   "whether the hub at " url " is running here or at the far end of a "
                   "tunnel. That distinction is the only thing standing between a "
                   "stale local hub and the real one, so this refuses rather than "
                   "guesses.")}

    (str/blank? (str theirs))
    {:problem :hub-hostname-unknown
     :message (str "the hub at " url " did not say which machine it is on. Either it is "
                   "older than this check -- deploy the current jar to it -- or its own "
                   "hostname could not be determined. Until it says, a hub left running "
                   "on this machine is indistinguishable from the one through the "
                   "tunnel, so this refuses rather than guesses.")}

    (and elected? (not= mine theirs))
    {:problem :elected-but-hub-is-elsewhere
     :message (str "this machine has " config/primary-marker " beside its config.edn, so "
                   "it is meant to hold the database -- but the hub answering " url
                   " says it is on " (pr-str theirs) " and this machine is "
                   (pr-str mine) ". Something else is holding local :3008 in front of "
                   "the hub that belongs here, most likely a leftover ssh tunnel. Stop "
                   "it (rhizome-stop), then start the hub here.")}

    (and (not elected?) (= mine theirs))
    {:problem :not-elected-but-hub-is-here
     :message (str "the hub answering " url " is running on THIS machine (" (pr-str mine)
                   "), and this machine has no " config/primary-marker " -- so it is not "
                   "the machine that holds the database. This is a hub left running from "
                   "when this machine was the hub, and it is answering on the address the "
                   "ssh tunnel should be on. Booting against it would read and write a "
                   "stale copy of the database, silently. Run rhizome-stop to kill it, "
                   "and let launchd put the tunnel back.")}

    :else nil))

(defn check-same-machine!
  "Throw when `/health`'s answer and this machine's marker disagree about which
   machine the hub is on. See `hub-identity-problem` for the rule and the
   failure.

   **Dev is exempt, as it is everywhere the marker is read.** No checkout has a
   marker (`primary.nosync` is gitignored) and `make start` runs a hub and a
   `server` on one machine, which is the `:not-elected-but-hub-is-here` shape
   exactly -- so without this exemption the dev stack and the whole e2e suite
   would refuse to boot. The same exemption is in `check-elected!` for the same
   reason."
  [url health]
  (when-not (:dev? config/config)
    (when-let [{:keys [message] :as problem}
               (hub-identity-problem {:elected? (config/primary-marker-present?)
                                      :mine     @config/hostname
                                      :theirs   (:hostname health)
                                      :url      url})]
      (let [msg (str "Refusing to start: " message)]
        (log/error (dissoc problem :message) msg)
        (throw (ex-info msg (assoc problem :hub/url url)))))))

(defn- check-hub!
  "One `/health` call, before anything else needs the hub, so that a hub that is
  not there says so in one line instead of surfacing as a connection refused in
  the middle of the first request.

  It used to be `SELECT 1` over the statement protocol. `/health` says the same
  thing -- something is listening and it has a database open -- without this
  process needing to speak SQL to ask.

  Its answer used to be handed to a role check as well: the two processes
  compared their verdicts about whether this instance might write, because they
  read the same marker in two different working directories and could disagree.
  There is nothing to disagree about now -- a hub that boots is writable, and a
  machine that was not elected does not boot one (`et.rz.hub.main/check-elected!`).

  It is not a health check with a retry loop: waiting for the hub is the start
  procedure's job, which polls `/health` before it starts this process at all.
  This is the message for when that did not happen -- a server started by hand,
  a server pointed at the wrong port, or, on a machine that is not the hub's, a
  tunnel that is down.

  **Refusing is deliberate, and it is not the harsh option.** This process can
  run without a hub only in the sense that it can answer the frontend; every
  navigation in it would 502, which reads as \"rhizome is broken\" rather than
  \"the hub is not up\". And the refusal costs nothing where it happens under a
  supervisor: a LaunchAgent with KeepAlive turns it into a wait loop that
  succeeds as soon as the tunnel comes back. See the README's \"Reaching the hub
  from another machine\".

  That last part is why `hub-proxy/health` has a ceiling of its own, shorter
  than the one a forwarded request gets. A wait loop needs this process to
  *exit*, and a half-dead tunnel accepts connections and answers nothing -- so
  without a socket timeout this call would not return, launchd would have
  nothing to restart, and the machine would sit there starting forever. The
  wait loop is a property of `health-request-defaults`, not of the refusal.

  **One call, two questions.** The answer is not thrown away: it goes straight
  to `check-same-machine!`, which asks whether the hub that answered is the one
  this machine should be talking to at all. A hub answering `/health` used to
  be the whole of \"the hub is up\", and on the one machine that has been the
  hub and is not any more, that was not enough -- see `hub-identity-problem`."
  []
  (when-let [url (hub-proxy/hub-url)]
    (check-same-machine!
      url
      (try (hub-proxy/health url)
           (catch Throwable t
             (let [msg (str "Refusing to start: no hub answering at "
                            url " (" (.getMessage t) "). "
                            "On the hub's own machine, start it first -- 'make start' "
                            "does, and 'make start-hub' runs it alone. On any other "
                            "machine this address is the near end of an ssh tunnel, so "
                            "what is down is the tunnel, not the hub.")]
               (log/error t msg)
               (throw (ex-info msg {:hub/url url} t))))))))

(defn start-http-server!
  []
  (when (and (not (:dev? config/config))
             (or (nil? (:private-addr config/config))
                 (not (string? (:private-addr config/config)))))
    (throw (Exception. "config invalid")))
  (check-hub!)
  (prepare-for-writing!)
  ;; The file-type context gate moved to the hub with the seed it depends on
  ;; (et.rz.hub.main/-main): both are questions about the contents of the database,
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
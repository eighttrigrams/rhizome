(ns et.rz.server.hub-proxy
  "Forwarding `server`'s item surfaces to the hub (arch rework 2, step 3).

   `server` keeps the routes that are about *this machine* — the frontend, the
   image bytes, `/open`, `/upload` — and hands everything about items to the
   process that owns the database. On the mini that is a loopback hop; from
   another machine it is the SSH tunnel.

   ## When it proxies, and when it does not

   Off `:hub-url`, read at call time. One key, one fact — where the hub is — and
   its absence is the whole of “there is no hub”:

   - set, and this process forwards the item surfaces there;
   - absent, and this process holds the database itself, which is what test mode
     is: one JVM, an in-memory database no other process could open, answering
     `/api` and `/ui` here exactly as before.

   It used to ask `db/remote?` about this process's own db handle, which worked
   while that handle was the only thing that knew. It retired with the facade's
   remote half (step 4): the `server` reaches no database at all now, so `:db`
   is nil outside test mode and the hub's address is a key of its own. No mode
   flag is introduced, and no configuration decides this twice."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-http.conn-mgr :as conn-mgr]
            [clojure.string :as str]
            [et.rz.config :as config]))

(def ^:private conn-manager
  "Its own pool, deliberately not `db`'s: that one is sized for statement
   traffic and retires with the statement protocol."
  (delay (conn-mgr/make-reusable-conn-manager {:timeout 30 :threads 32 :default-per-route 8})))

(def request-defaults
  "How long a call on this wire may take before it is an error, and what must
   never be retried. Everything that leaves this process merges these.

   The pool line above carries `:timeout 30`, which is the pool's *idle TTL* --
   how long an unused socket is kept, not how long a request may take. It was
   copied over from the retired `db` namespace together with the pool, and the
   request timeouts that sat beside it there were not. This is them arriving
   back, in the terms the new seam needs.

   **`:connection-timeout` -- how long to wait to *reach* the hub.** Loopback
   either connects at once or not at all, and that is still true through an
   `ssh -L`, because the near end of the tunnel is a local socket. Two seconds
   is therefore not a guess about the network; it is a bound on a thing that is
   normally instantaneous.

   **`:socket-timeout` -- how long the hub may stay silent.** This is the one
   that was missing and the only one that catches the failure that matters. A
   *closed* port answers connection-refused and was always a fast 502; a
   half-dead tunnel **accepts and never answers**, which no connection timeout
   can see. Without a socket timeout such a call hangs forever, holding the
   jetty thread that is serving a browser, and with a page's worth of images in
   flight that is the whole thread pool. The failure is not theoretical: it
   reproduces against a `ServerSocket` that accepts and writes nothing.

   Sixty seconds rather than something snappier, because it is an *inactivity*
   bound on real hub-side work and the slowest legitimate case is not close to
   interactive: inserting a URL runs a website scrape (10s socket + 5s connect
   in `scrapers.website`) and then ImageMagick, and a semantic search embeds the
   query through ollama (10s + 2s in `semsearch.embedder`). A ceiling below
   those would turn a slow website into a failed insert -- trading a hang the
   `server` recovers from for a write the human has to notice. Boot is where
   fast failure belongs, and `health-request-defaults` below is where it is.

   **`:retry-handler`, off**, which is carried over from the retired `db`
   namespace verbatim and for its reason. Apache HttpClient retries a request
   whose connection died without answering, reasoning that a pooled connection
   the server had already closed never delivered it -- usually true, and
   unknowable from this end. What crosses here is no longer a statement but a
   whole dispatch call or `/api` write, so the case where that reasoning is
   wrong is an insert that ran and is run again. A visible error on a stale
   connection is the better trade: this seam exists to keep failures from being
   silent, and a duplicated item is as silent as they come."
  {:connection-timeout         2000
   :connection-request-timeout 5000
   :socket-timeout             60000
   :retry-handler              (fn [_ex _try-count _context] false)})

(def health-request-defaults
  "`/health`'s own, shorter, ceiling -- five seconds of silence instead of
   sixty.

   `/health` is the one call whose *whole job* is to fail fast. It is what
   `et.rz.server.main/check-hub!` asks at boot, and the README promises that under a
   LaunchAgent with `KeepAlive` the refusal to start 'is simply a wait loop'.
   That promise needs the process to exit, and against a half-dead tunnel it
   only exits if this call gives up. Launchd's `ThrottleInterval` in the
   README's plist is ten seconds, so five here means the wait loop turns over
   at roughly the rate the README already describes.

   The hub answers `/health` off state it read at its own startup -- no query,
   no embed -- so there is no slow legitimate case to leave room for."
  (assoc request-defaults :socket-timeout 5000))

(def unbounded-paths
  "Forwarded paths that are allowed to be silent for as long as they like.

   `POST /api/backfill/embeddings` embeds every item with a description and no
   embedding, one ollama call at a time, and answers when it is finished. On the
   live database that is minutes, and it is the documented behaviour of the
   endpoint -- `.claude/skills/rhizome-rest-api/SKILL.md` says in as many words
   that *the request blocks until completion*, and
   `et.rz.hub.rest-api.mutations/backfill-embeddings` is where it does. Sixty seconds of silence is not a symptom there, it is the
   normal case, so the ceiling is lifted rather than guessed at -- the same
   judgement the retired `db` namespace recorded about statements: a timeout
   that does not know what it is timing has no business naming a number.

   Lifting it costs exactly what it says. A backfill sent through a `server`
   whose tunnel has gone dark hangs until something else notices. That is one
   deliberate, human-initiated call, not a browser navigation, and it is the
   only shape on this surface with no interactive deadline of its own.

   `:connection-timeout` still applies, so a hub that is *not listening* still
   fails at once here like everywhere else."
  #{"/api/backfill/embeddings"})

(defn hub-url
  "The hub's base URL, or nil when this process holds the database itself."
  []
  (:hub-url config/config))

(defn health
  "The hub's `/health`, parsed, or a throw naming the url that did not answer.

   Plain JSON on a plain GET, which is what makes this the right thing for a
   startup check: it is the one route a start script or a prober can read
   without speaking any protocol at all."
  [url]
  (let [resp (http/get (str url "/health")
                       (merge health-request-defaults
                              {:as :string :throw-exceptions false
                               :connection-manager @conn-manager}))]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "hub: /health answered " (:status resp) " at " url)
                      {:hub-url url :status (:status resp)})))
    (json/parse-string (:body resp) true)))

(def ^:private forwarded-request-headers
  "Only what the far end reads. Not `host` (it names this machine), not
   `content-length` or `transfer-encoding` (the client recomputes them), and
   not `connection` (it is about this hop, not the next one)."
  #{"content-type" "accept" "accept-charset"})

(def ^:private forwarded-response-headers
  #{"content-type"})

(defn forward
  "Send a ring request to `url` unchanged in everything that matters — method,
   path, query string, body — and answer what came back.

   The body is passed through as the stream it arrived as, so nothing here has
   to know whether it is JSON, transit or bytes. Responses come back as a byte
   array for the same reason.

   A hub that cannot be reached answers 502 rather than a stack trace: from
   another machine that is the ordinary case of a tunnel that is down, and it
   is the `server`'s job to say so in a sentence."
  [url req]
  (let [target (str url (:uri req) (when-let [q (:query-string req)] (str "?" q)))]
    (try
      (let [resp (http/request
                   (merge (cond-> request-defaults
                            (unbounded-paths (:uri req)) (dissoc :socket-timeout))
                          {:method           (:request-method req)
                           :url              target
                           :headers          (select-keys (:headers req) forwarded-request-headers)
                           :body             (:body req)
                           :as               :byte-array
                           :throw-exceptions false
                           :connection-manager @conn-manager}))]
        {:status  (:status resp)
         :headers (select-keys (:headers resp) forwarded-response-headers)
         :body    (:body resp)})
      (catch Exception e
        {:status  502
         :headers {"Content-Type" "application/json"}
         :body    (str "{\"error\":\"rhizome: the hub at " url " could not be reached: "
                       (str/replace (str (.getMessage e)) #"\"" "'")
                       "\",\"hub-url\":\"" url "\"}")}))))

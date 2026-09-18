(ns hub-proxy
  "Forwarding `server`'s item surfaces to the hub (arch rework 2, step 3).

   `server` keeps the routes that are about *this machine* — the frontend, the
   image bytes, `/open`, `/upload` — and hands everything about items to the
   process that owns the database. On the mini that is a loopback hop; from
   another machine it is the SSH tunnel.

   ## When it proxies, and when it does not

   Off `db/remote?`, asked of the process's own handle at call time. That is
   not a convenience: it is the same question, asked once, in the one place the
   answer already lives.

   - a handle like `{:db-server/url \"http://127.0.0.1:3008\"}` means there is a
     hub over there and this process should be asking it;
   - a local DataSource means this process *is* the one holding the file — which
     is what the test and e2e modes hand it, both of which run one process with
     no hub to talk to. They keep answering `/api` themselves, exactly as
     before.

   So no mode flag is introduced, and no configuration decides this twice."
  (:require [clj-http.client :as http]
            [clj-http.conn-mgr :as conn-mgr]
            [clojure.string :as str]
            [config :as config]
            [db :as db]))

(def ^:private conn-manager
  "Its own pool, deliberately not `db`'s: that one is sized for statement
   traffic and retires with the statement protocol."
  (delay (conn-mgr/make-reusable-conn-manager {:timeout 30 :threads 32 :default-per-route 8})))

(defn hub-url
  "The hub's base URL, or nil when this process holds the database itself."
  []
  (let [handle (:db config/config)]
    (when (db/remote? handle) (:db-server/url handle))))

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
                   {:method           (:request-method req)
                    :url              target
                    :headers          (select-keys (:headers req) forwarded-request-headers)
                    :body             (:body req)
                    :as               :byte-array
                    :throw-exceptions false
                    :connection-manager @conn-manager})]
        {:status  (:status resp)
         :headers (select-keys (:headers resp) forwarded-response-headers)
         :body    (:body resp)})
      (catch Exception e
        {:status  502
         :headers {"Content-Type" "application/json"}
         :body    (str "{\"error\":\"rhizome: the hub at " url " could not be reached: "
                       (str/replace (str (.getMessage e)) #"\"" "'")
                       "\",\"hub-url\":\"" url "\"}")}))))

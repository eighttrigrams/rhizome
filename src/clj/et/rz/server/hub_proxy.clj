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
                       {:as :string :throw-exceptions false
                        :connection-manager @conn-manager})]
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

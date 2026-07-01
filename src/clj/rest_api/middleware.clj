(ns rest-api.middleware
  (:require [cambium.core :as log]
            [cheshire.core :as json]
            [clojure.string :as str]
            [config :as config]))

(defonce ^:private *recording? (atom false))

(defn enabled?
  "Recording mode is on either when toggled on at runtime, or whenever
   the server is running with `:dev? true` in config — in dev the REST
   API is always open."
  []
  (or (:dev? config/config) @*recording?))

(defn toggle! [] (swap! *recording? not))

(defn set-recording! [v] (reset! *recording? (boolean v)))

(defn log-and-guard
  "Log the intended write action, then either run `thunk` (when recording)
   or drop the request silently and return `dropped-response` instead.
   The log line does not reveal whether the request actually executed —
   it records the intent identically either way."
  [intent details dropped-response thunk]
  (log/info (assoc details :intent intent) (str "REST " intent))
  (if (enabled?) (thunk) dropped-response))

(def ^:private max-body-log-chars 4000)

(defn- truncate
  [s]
  (when s
    (if (> (count s) max-body-log-chars)
      (str (subs s 0 max-body-log-chars) "…[truncated " (- (count s) max-body-log-chars) " chars]")
      s)))

(defn- read-and-restore-body
  [req]
  (if-let [b (:body req)]
    (let [s (try (slurp b) (catch Exception _ nil))]
      (if s
        [s (assoc req :body (java.io.ByteArrayInputStream. (.getBytes s "UTF-8")))]
        [nil req]))
    [nil req]))

(defn- abbreviate-descriptions
  "Walk a parsed JSON map/seq for log-friendliness:
   - any :description string value is replaced with \"<N chars>\";
   - any :contexts map is reduced to its key-set (titles dropped, only ids
     remain) since titles in log lines are noise;
   Recurses through nested maps and collections."
  [x]
  (cond
    (map? x) (reduce-kv (fn [acc k v]
                          (let [desc? (or (= "description" k) (= :description k))
                                ctxs? (or (= "contexts" k) (= :contexts k))]
                            (assoc acc k
                              (cond
                                (and desc? (string? v)) (str "<" (count v) " chars>")
                                (and ctxs? (map? v)) (vec (keys v))
                                :else (abbreviate-descriptions v)))))
                        {} x)
    (sequential? x) (mapv abbreviate-descriptions x)
    :else x))

(defn- abbreviate-descriptions-in-json
  "If s parses as JSON, return it re-serialised with :description fields
  collapsed to char-counts; otherwise return s untouched."
  [s]
  (or (try (-> s (json/parse-string) abbreviate-descriptions json/generate-string)
           (catch Exception _ nil))
      s))

(defn- response-body-str
  "Stringify a response body for logging without consuming streams.
  Long :description fields are abbreviated to char-counts so the log
  line stays scannable."
  [body]
  (cond
    (nil? body) nil
    (string? body) (abbreviate-descriptions-in-json body)
    (coll? body) (try (pr-str (abbreviate-descriptions body)) (catch Exception _ nil))
    :else nil))

(def ^:private mutation-methods
  "HTTP methods treated as mutations and therefore required to carry a
  non-blank :reason in the JSON body."
  #{:post :put :patch :delete})

(defn- rest-uri?
  [req]
  (let [uri (or (:uri req) "")]
    (or (= uri "/rest") (str/starts-with? uri "/rest/"))))

(defn- missing-reason-response
  []
  {:status 400
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
           {:error
              (str "\"reason\" is required in the JSON body "
                   "(non-empty string). See GET /rest/describe.")})})

(defn- parse-reason
  [body-str]
  (try (when (and body-str (not (str/blank? body-str)))
         (:reason (json/parse-string body-str true)))
       (catch Exception _ nil)))

(defn- require-reason
  [handler req]
  (let [body-str (try (slurp (:body req)) (catch Exception _ nil))
        req'    (assoc req :body (java.io.ByteArrayInputStream.
                                   (.getBytes (or body-str "") "UTF-8")))
        reason  (parse-reason body-str)]
    (if (and (string? reason) (not (str/blank? reason)))
      (handler req')
      (missing-reason-response))))

(defn wrap-require-reason
  "For mutation requests (POST/PUT/PATCH/DELETE) under /rest, require a
  non-blank \"reason\" field in the JSON body. Reads the body once,
  validates, then restores it as a ByteArrayInputStream so downstream
  handlers can re-slurp normally. Read-only methods (GET, HEAD, OPTIONS)
  and non-/rest URIs pass through untouched. The rule is documented
  globally in /rest/describe so individual handlers don't have to
  mention it."
  [handler]
  (fn [req]
    (if (and (rest-uri? req) (mutation-methods (:request-method req)))
      (require-reason handler req)
      (handler req))))

(defn- log-request
  [{:keys [method uri qs remote ua req-body]}]
  (log/info (cond-> {:event "rest-request"
                     :method method
                     :uri uri}
              qs (assoc :query-string qs)
              remote (assoc :remote-addr remote)
              ua (assoc :user-agent ua)
              (and req-body (not (str/blank? req-body))) (assoc :request-body (truncate req-body)))
            (str "REST " method " " uri (when qs (str "?" qs)))))

(defn- log-response
  [{:keys [method uri response duration]}]
  (let [status    (:status response)
        resp-body (response-body-str (:body response))]
    (log/info (cond-> {:event "rest-response"
                       :method method
                       :uri uri
                       :status status
                       :duration-ms duration}
                resp-body (assoc :response-body (truncate resp-body)))
              (str "REST " method " " uri " -> " status " (" duration "ms)"))))

(defn- log-around
  [handler req]
  (let [start  (System/currentTimeMillis)
        method (some-> req :request-method name str/upper-case)
        uri    (:uri req)
        qs     (:query-string req)
        remote (:remote-addr req)
        ua     (get-in req [:headers "user-agent"])
        [req-body req'] (read-and-restore-body req)]
    (log-request {:method method :uri uri :qs qs :remote remote :ua ua :req-body req-body})
    (let [response (try (handler req')
                        (catch Throwable t
                          (log/error t (str "REST " method " " uri " threw"))
                          (throw t)))
          duration (- (System/currentTimeMillis) start)]
      (log-response {:method method :uri uri :response response :duration duration})
      response)))

(defn wrap-logging
  "Ring middleware that logs every /rest interaction (request + response)
   under the `rest-api.middleware` logger. Captures method, URI, query
   string, request body (truncated), response status, response body
   (truncated) and duration. Non-/rest URIs pass through untouched.
   Bodies are restored as ByteArrayInputStreams so downstream handlers
   can still slurp them."
  [handler]
  (fn [req]
    (if (rest-uri? req)
      (log-around handler req)
      (handler req))))

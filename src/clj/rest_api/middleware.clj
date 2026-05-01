(ns rest-api.middleware
  (:require [cambium.core :as log]
            [clojure.string :as str]))

(defonce ^:private *recording? (atom false))

(defn enabled? [] @*recording?)

(defn toggle! [] (swap! *recording? not))

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

(defn- response-body-str
  "Stringify a response body for logging without consuming streams."
  [body]
  (cond
    (nil? body) nil
    (string? body) body
    (coll? body) (try (pr-str body) (catch Exception _ nil))
    :else nil))

(defn wrap-logging
  "Ring middleware that logs every REST API interaction (request + response)
   under the `rest-api.middleware` logger. Captures method, URI, query string,
   request body (truncated), response status, response body (truncated) and
   duration. Bodies are restored as ByteArrayInputStreams so downstream
   handlers can still slurp them."
  [handler]
  (fn [req]
    (let [start (System/currentTimeMillis)
          method (some-> req :request-method name str/upper-case)
          uri (:uri req)
          qs (:query-string req)
          remote (:remote-addr req)
          ua (get-in req [:headers "user-agent"])
          [req-body req'] (read-and-restore-body req)]
      (log/info (cond-> {:event "rest-request"
                         :method method
                         :uri uri}
                  qs (assoc :query-string qs)
                  remote (assoc :remote-addr remote)
                  ua (assoc :user-agent ua)
                  (and req-body (not (str/blank? req-body))) (assoc :request-body (truncate req-body)))
                (str "REST " method " " uri (when qs (str "?" qs))))
      (let [response (try (handler req')
                          (catch Throwable t
                            (log/error t (str "REST " method " " uri " threw"))
                            (throw t)))
            duration (- (System/currentTimeMillis) start)
            status (:status response)
            resp-body (response-body-str (:body response))]
        (log/info (cond-> {:event "rest-response"
                           :method method
                           :uri uri
                           :status status
                           :duration-ms duration}
                    resp-body (assoc :response-body (truncate resp-body)))
                  (str "REST " method " " uri " -> " status " (" duration "ms)"))
        response))))

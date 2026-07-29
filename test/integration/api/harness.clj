(ns api.harness
  "Test harness for the /ui endpoint consumed by the UI.

   Tests should call `call!` with plain Clojure data; this namespace owns
   the wire format (currently: JSON envelope wrapping transit-encoded args
   and return — matching net.eighttrigrams/defn-over-http). If the
   transport ever changes, only this file should need to follow."
  (:require [cheshire.core :as json]
            [cognitect.transit :as transit]
            [ring.mock.request :as mock]
            [ring.middleware.json :as ring-json]
            [ring.util.response :as response]
            [dispatch :as dispatch]
            [et.vp.ds.search-test :refer [db]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn- transit-write [v]
  (let [os (ByteArrayOutputStream. 4096)
        w  (transit/writer os :json)]
    (transit/write w v)
    (.toString os "UTF-8")))

(defn- transit-read [^String s]
  (let [is (ByteArrayInputStream. (.getBytes s "UTF-8"))]
    (transit/read (transit/reader is :json))))

(defn- app-for
  "The /ui app, serving `db` as the dispatcher's server-args. Parameterised
   because a read-only replica's datasource is a different one (see
   api.replica-query-sweep-test)."
  [db]
  (-> (fn [req]
        (response/response
          (dispatch/handler
            (assoc-in req [:body :server-args :db] db))))
      ring-json/wrap-json-response
      (ring-json/wrap-json-body {:keywords? true})))

(def app (app-for db))

(defn call-on!
  "Like `call!`, but against a caller-supplied datasource."
  [db fn-name & args]
  (let [body (json/generate-string
               {:fn   (name fn-name)
                :args (transit-write (vec args))})
        req  (-> (mock/request :post "/ui")
                 (mock/content-type "application/json")
                 (mock/body body))
        resp ((app-for db) req)
        parsed (json/parse-string (:body resp) true)]
    (when-let [thrown (:thrown parsed)]
      (throw (ex-info (str "API error: " thrown)
                      {:fn fn-name :args args :status (:status resp)})))
    (transit-read (:return parsed))))

(defn call!
  "Invoke a dispatch function as the UI would. Args are positional Clojure
   values; the return value is the dispatched fn's return as plain data.
   Throws ex-info when the server reports `:thrown`."
  [fn-name & args]
  (apply call-on! db fn-name args))

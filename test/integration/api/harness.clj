(ns api.harness
  "Test harness for the /api endpoint consumed by the UI.

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

(defn- inner-handler [req]
  (response/response
    (dispatch/handler
      (assoc-in req [:body :server-args :db] db))))

(def app
  (-> inner-handler
      ring-json/wrap-json-response
      (ring-json/wrap-json-body {:keywords? true})))

(defn call!
  "Invoke a dispatch function as the UI would. Args are positional Clojure
   values; the return value is the dispatched fn's return as plain data.
   Throws ex-info when the server reports `:thrown`."
  [fn-name & args]
  (let [body (json/generate-string
               {:fn   (name fn-name)
                :args (transit-write (vec args))})
        req  (-> (mock/request :post "/api")
                 (mock/content-type "application/json")
                 (mock/body body))
        resp (app req)
        parsed (json/parse-string (:body resp) true)]
    (when-let [thrown (:thrown parsed)]
      (throw (ex-info (str "API error: " thrown)
                      {:fn fn-name :args args :status (:status resp)})))
    (transit-read (:return parsed))))

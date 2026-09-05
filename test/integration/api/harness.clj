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
            [db-harness]
            [dispatch :as dispatch]
            [et.vp.ds.search-test])
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
   api.replica-query-sweep-test) -- and because `call-on!` exists for callers
   that have a handle of their own, which the facade carries whichever kind it
   is."
  [db]
  (-> (fn [req]
        (response/response
          (dispatch/handler
            (assoc-in req [:body :server-args :db] db))))
      ring-json/wrap-json-response
      (ring-json/wrap-json-body {:keywords? true})))

(defn call-on!
  "Like `call!`, but against a caller-supplied handle -- a datasource a test
   built for itself, most often a read-only one. Those stay local: the facade's
   local branch carries them exactly as it did, and forcing them through a
   db-server would mean rewriting the test bodies that construct them."
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
   Throws ex-info when the server reports `:thrown`.

   What the app is handed is a **remote** handle: everything a dispatched
   function does to the database leaves this process over HTTP and is executed
   by a db-server, against the same in-memory database the calling test reads
   and writes directly. See `db-harness` -- two names, one database, and no
   test body the wiser."
  [fn-name & args]
  (apply call-on! db-harness/remote fn-name args))

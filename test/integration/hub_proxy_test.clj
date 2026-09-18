(ns hub-proxy-test
  "`server` hands the item surfaces to the hub (arch rework 2, step 3).

   The test stands the pair up for real: a hub on an ephemeral port with its
   own file database, and `server`'s ring app built against a *remote* handle
   pointing at it. Nothing is stubbed, so what is asserted is the thing that
   has to be true from another machine -- a request arriving at this machine's
   `/api` is answered out of the database on the other one.

   The discriminator throughout is that the hub's database and the test-mode
   global database are different databases. A context written straight into the
   hub is invisible to the global handle, so an answer that contains it can
   only have come from the hub."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.test :refer [deftest is testing]]
            [cognitect.transit :as transit]
            [config :as config]
            [db-server]
            [server]))

(defn- temp-db-path []
  (.getAbsolutePath (doto (java.io.File/createTempFile "rhizome-proxy-test" ".db")
                      (.deleteOnExit))))

(defn- with-pair
  "A live hub, and `server`'s app pointed at it by a remote handle."
  [f]
  (let [hub (db-server/start! {:port 0 :db-path (temp-db-path)})]
    (try
      (with-redefs [config/config (assoc config/config
                                    :db {:db-server/url (:url hub)}
                                    :dev? true)]
        (f hub (server/app)))
      (finally (db-server/stop! hub)))))

(defn- GET* [app uri]
  (let [resp (app {:request-method :get :uri uri :headers {} :body nil})]
    [(:status resp)
     (let [b (:body resp)]
       (json/parse-string (if (bytes? b) (String. ^bytes b "UTF-8") (str b)) true))]))

(defn- seed-context!
  "Write a context straight into the hub, through its own /ui."
  [hub title]
  (let [os (java.io.ByteArrayOutputStream. 512)]
    (transit/write (transit/writer os :json) [nil {:title title}])
    (http/post (str (:url hub) "/ui")
                          {:body (json/generate-string {:fn "insert-context"
                                                        :args (.toString os "UTF-8")})
                           :content-type :json :accept :json :as :string})))

(deftest api-is-answered-by-the-hub-test
  (with-pair
    (fn [hub app]
      (seed-context! hub "OnTheHub")
      (testing "a GET to this machine's /api comes back with the hub's rows"
        (let [[status body] (GET* app "/api/contexts")]
          (is (= 200 status))
          (is (some #(= "OnTheHub" (:title %)) body)
              (str "the answer did not come from the hub. Got: " (pr-str body))))))))

(deftest the-describe-is-not-forwarded-test
  (with-pair
    (fn [_hub app]
      (testing "/api/describe is answered here, not by the hub"
        ;; The hub still answers that path with the statement protocol's
        ;; description (db-server registered it first, and prober reads it).
        ;; Forwarding it would hand agents the wrong document. When step 4
        ;; retires the protocol this test is what says the exception can go.
        (let [[status body] (GET* app "/api/describe")]
          (is (= 200 status))
          (is (not-any? #(= "execute" (:name %)) (:endpoints body))
              (str "the statement protocol's describe leaked through the proxy. "
                   "Got: " (pr-str (map :name (:endpoints body))))))))))

(deftest a-hub-that-is-not-there-answers-502-test
  (testing "a tunnel that is down is an answer, not a stack trace"
    (with-redefs [config/config (assoc config/config
                                  ;; a port nothing is listening on
                                  :db {:db-server/url "http://127.0.0.1:1"}
                                  :dev? true)]
      (let [[status body] (GET* (server/app) "/api/contexts")]
        (is (= 502 status))
        (is (re-find #"could not be reached" (str (:error body))))))))

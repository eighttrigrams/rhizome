(ns db-server.hub-surfaces-test
  "The hub answers `/ui` and `/api` off its own database (arch rework 2,
   step 2).

   Until now the db-server spoke statements and nothing else -- `db_server`'s
   own docstring said there would never be an endpoint here that mentioned an
   item. That is the sentence this step reverses, so it deserves a test that
   reverses it visibly: a context created through this server's `/ui` and read
   back through this server's `/api`, with no app-server anywhere in the
   picture and no remote handle in between.

   The test-mode global handle (`config/config`'s `:db`) points at a shared
   in-memory database that is NOT this server's file, which is what makes the
   round trip mean something: if either surface had kept reading the global,
   the write would land in one database and the read would come back empty
   from the other."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.test :refer [deftest is testing]]
            [cognitect.transit :as transit]
            [db-server]
            [placement :as placement])
  (:import [java.io ByteArrayOutputStream]))

(defn- temp-db-path []
  (.getAbsolutePath (doto (java.io.File/createTempFile "rhizome-hub-test" ".db")
                      (.deleteOnExit))))

(defn- with-hub [f]
  (let [server (db-server/start! {:port 0 :db-path (temp-db-path)})]
    (try (f server) (finally (db-server/stop! server)))))

(defn- transit-args
  "The `/ui` envelope carries its args as a transit string."
  [args]
  (let [os (ByteArrayOutputStream. 512)]
    (transit/write (transit/writer os :json) args)
    (.toString os "UTF-8")))

(defn- ui!
  "POST a `/ui` command, and answer the dispatcher's envelope."
  [server fn-name args]
  (let [resp (http/post (str (:url server) "/ui")
                        {:body             (json/generate-string
                                             {:fn fn-name :args (transit-args args)})
                         :content-type     :json
                         :accept           :json
                         :as               :string
                         :throw-exceptions false})]
    (assoc (json/parse-string (:body resp) true) :status (:status resp))))

(defn- api-get [server path]
  (let [resp (http/get (str (:url server) path)
                       {:as :string :throw-exceptions false})]
    [(:status resp) (json/parse-string (:body resp) true)]))

(deftest the-hub-serves-both-item-surfaces-off-its-own-db-test
  (with-hub
    (fn [server]
      (testing "a context written through /ui comes back through /api"
        (let [{:keys [thrown status]} (ui! server "insert-context" [nil {:title "Hub"}])]
          (is (= 200 status))
          (is (nil? thrown) (str "the /ui call failed: " thrown)))
        (let [[status body] (api-get server "/api/contexts")]
          (is (= 200 status))
          ;; /api/contexts answers a bare array, not an envelope.
          (is (some #(= "Hub" (:title %)) body)
              (str "the context written through this server's /ui was not visible "
                   "through this server's /api -- one of the two surfaces is "
                   "reading a different database. Body was: " (pr-str body))))))))

(deftest the-hub-refuses-machine-local-commands-test
  ;; `placement/machine-local-commands` is **empty** since Obsidian support was
  ;; removed (see placement), so there is no real command to send here. The
  ;; guard is what is under test, not the classification, so the classification
  ;; is stubbed: any command named machine-local must be refused by the hub
  ;; rather than answered. Without this the mechanism would sit untested until
  ;; the next machine-local command is added -- which is exactly when it needs
  ;; to already work.
  (with-redefs [placement/machine-local-commands #{"fetch-item-description"}]
    (with-hub
      (fn [server]
        (testing "a command that works on the human's own disk is refused, not run"
          (let [{:keys [thrown return]} (ui! server "fetch-item-description" [{} {:id 1}])]
            (is (nil? return))
            (is (some? thrown) "a machine-local command must not be answered here")
            (is (re-find #"machine-local" (str thrown))
                (str "the refusal should say why. Got: " (pr-str thrown)))))
        (testing "and a hub command on the same surface still goes through"
          (is (nil? (:thrown (ui! server "list-resources" [{}])))))))))

(deftest the-db-server-describe-still-wins-test
  (with-hub
    (fn [server]
      (testing "GET /api/describe is still the statement protocol's own"
        ;; Both surfaces claim this path and the first registered wins (see the
        ;; comment in db-server/app). Pinning it means the swap at step 4 --
        ;; when the statement protocol retires and rhizome's describe should
        ;; take the path over -- is a decision someone makes, not a surprise
        ;; someone discovers.
        (let [[status body] (api-get server "/api/describe")]
          (is (= 200 status))
          ;; Both describes answer {:endpoints … :skill …} -- that shape is the
          ;; plurama convention and db-server was built to match it, so the
          ;; keys cannot tell them apart. The contents can: the db-server lists
          ;; its own vars by name, rhizome lists REST paths.
          (is (some #(= "execute" (:name %)) (:endpoints body))
              (str "/api/describe should still be the statement protocol's. If "
                   "this fails, rhizome's REST describe has taken the path over "
                   "-- which is step 4's job, and this test goes with it. Got: "
                   (pr-str (map :name (:endpoints body))))))))))

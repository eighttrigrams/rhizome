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
            [et.rz.placement :as placement])
  (:import [java.io ByteArrayOutputStream]))

(defn- temp-db-path []
  (.getAbsolutePath (doto (java.io.File/createTempFile "rhizome-hub-test" ".db")
                      (.deleteOnExit))))

(defn- with-hub
  ([f] (with-hub {} f))
  ([opts f]
   (let [server (db-server/start! (merge {:port 0 :db-path (temp-db-path)
                                          :allow-reset? true}
                                         opts))]
     (try (f server) (finally (db-server/stop! server))))))

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

(deftest the-describe-is-rhizomes-own-now-test
  (with-hub
    (fn [server]
      (testing "GET /api/describe answers the item API"
        ;; The statement protocol had a describe of its own and, being
        ;; registered first, answered this path. It was documented as temporary
        ;; the day it was written and pinned by a test, so that the handover
        ;; would be a decision rather than a surprise. This is the other side of
        ;; that test: the protocol is gone, and what prober and any agent read
        ;; here is rhizome's REST surface.
        (let [[status body] (api-get server "/api/describe")]
          (is (= 200 status))
          (is (some? (:endpoints body)))
          (is (not-any? #(= "execute" (:name %)) (:endpoints body))
              (str "the statement protocol's describe is still answering. Got: "
                   (pr-str (map :name (:endpoints body)))))
          ;; rhizome's describe names each endpoint by var and leads its doc
          ;; with the method and path, so the item routes are visible there.
          (is (some #(re-find #"/api/items" (str (:doc %))) (:endpoints body))
              "and the item routes are what it names"))))))

(defn- post-plain [server path]
  (let [resp (http/post (str (:url server) path) {:as :string :throw-exceptions false})]
    [(:status resp) (:body resp)]))

(deftest the-hub-empties-its-own-database-test
  (with-hub
    (fn [server]
      (testing "/test/reset deletes the rows this server holds"
        (is (nil? (:thrown (ui! server "insert-context" [nil {:title "Doomed"}]))))
        (is (= [200 "ok"] (post-plain server "/test/reset")))
        (let [[status body] (api-get server "/api/contexts")]
          (is (= 200 status))
          (is (empty? body)
              (str "the reset did not empty this server's database: " (pr-str body))))))))

(deftest a-production-hub-refuses-to-empty-itself-test
  ;; :allow-reset? comes from the config's top-level :dev?, so this is the world
  ;; of the mini: no :dev? in config.edn, and a route that deletes every row
  ;; must answer 403 rather than run. It was `server`'s gate before the reset
  ;; moved here, and it moved with it.
  (with-hub {:allow-reset? false}
    (fn [server]
      (is (nil? (:thrown (ui! server "insert-context" [nil {:title "Survivor"}]))))
      (is (= 403 (first (post-plain server "/test/reset"))))
      (let [[_ body] (api-get server "/api/contexts")]
        (is (some #(= "Survivor" (:title %)) body)
            "the row was deleted by a hub that should have refused")))))

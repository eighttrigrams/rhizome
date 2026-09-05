(ns rest-api.replica-test
  "Read-only replica mode over /api: mutations refused, reads untouched.
   A booted instance carries its role in `config/config` (decided once at
   startup, see config/read-only-replica?), so redefining that map is exactly
   what a replica -- or a primary -- looks like from the routes' side."
  (:require [clojure.test :refer [deftest is testing]]
            [db-harness]
            [cheshire.core :as json]
            [ring.mock.request :as mock]
            [ring.middleware.params :refer [wrap-params]]
            [config :as config]
            [rest-api :as rest-api]
            [rest-api.middleware :as mw]
            [et.vp.ds :as ds]
            [et.vp.ds.search-test :refer [reset-db with-time db]]))

(def ^:private handler (delay (wrap-params (rest-api/rest-routes))))

;; prod mode (no :dev?) plus the role the marker check produced.
;;
;; These two, and the dev-mode config built further down, all come from
;; `db-harness/app-config`: every handler this file stands up is given the
;; remote handle, so the writes that are supposed to land go over the wire, and
;; the ones that are supposed to be refused are refused before they reach a
;; handle of either kind. Said here rather than implied, because a comment over
;; two of three configs once read as speaking for all of them, and the third
;; was quietly writing to the local DataSource.
(def ^:private replica-config (db-harness/app-config-with {:read-only-replica? true}))
(def ^:private primary-config (db-harness/app-config-with {:read-only-replica? false}))

(defn- request*
  [cfg method path body]
  (with-redefs [config/config cfg]
    (@handler (cond-> (mock/request method path)
                body (-> (mock/content-type "application/json")
                         (mock/body (json/generate-string body)))))))

(defn- body-json [resp] (json/parse-string (:body resp) true))

(defmacro test-with-fresh-db
  [description & body]
  `(testing ~description (reset-db) (with-time ~@body)))

(def ^:private mutations
  [[:post "/api/contexts" {:title "ShouldNotPersist" :reason "test"}]
   [:post "/api/items" {:title "ShouldNotPersist" :context-ids [1] :reason "test"}]
   [:put "/api/relations" {:source-id 1 :target-id 2 :reason "test"}]
   [:post "/api/recording-mode/toggle" {:reason "test"}]
   [:post "/api/backfill/embeddings" {:reason "test"}]])

(deftest replica-refuses-every-api-mutation-test
  (test-with-fresh-db "prod + no marker: mutating methods get the graceful refusal"
    (doseq [[method path body] mutations]
      (let [resp (request* replica-config method path body)
            b (body-json resp)]
        (is (= 403 (:status resp)) (str method " " path))
        (is (true? (:read-only-replica b)) (str method " " path))
        (is (re-find #"read-only replica" (:error b)) (str method " " path))))
    (testing "nothing was written"
      (is (nil? (:id (ds/get-item-by-title db {:title "ShouldNotPersist"})))))
    (testing "and the recording-mode gate was not flipped by its refused toggle"
      (with-redefs [config/config replica-config]
        (is (false? (mw/enabled?)))))))

(deftest replica-refuses-mutations-without-a-reason-too-test
  (test-with-fresh-db "the refusal comes before the reason check, not after it"
    (let [resp (request* replica-config :post "/api/contexts" {:title "X"})]
      (is (= 403 (:status resp)))
      (is (true? (:read-only-replica (body-json resp)))))))

(deftest replica-serves-reads-test
  (test-with-fresh-db "prod + no marker: GETs are untouched"
    (let [ctx (ds/new-context db {:title "Books"})
          item (ds/new-item db "Sapiens" "" #{(:id ctx)} 1)]
      (is (= "Books" (:title (body-json (request* replica-config
                                                  :get (str "/api/items/" (:id ctx)) nil)))))
      (is (= 200 (:status (request* replica-config
                                    :get (str "/api/items/" (:id ctx) "/related") nil))))
      (is (= "Sapiens" (:title (body-json (request* replica-config
                                                    :get (str "/api/items/" (:id item)) nil)))))
      (is (= 200 (:status (request* replica-config :get "/api/contexts?q=Boo" nil))))
      (is (= 200 (:status (request* replica-config :get "/api/describe" nil)))))))

(deftest status-reports-the-role-test
  (testing "GET /api/status is how a caller (and the UI) learns the role"
    (is (true? (:read-only-replica (body-json (request* replica-config :get "/api/status" nil)))))
    (is (false? (:read-only-replica (body-json (request* primary-config :get "/api/status" nil)))))))

(deftest describe-states-the-rule-test
  (testing "an agent reading the API learns that replicas refuse writes"
    (let [conventions (:conventions (body-json (request* replica-config :get "/api/describe" nil)))]
      (is (some #(re-find #"read-only replica" %) conventions))
      (is (some #(re-find #"primary\.nosync" %) conventions)))
    (testing "and so does the skill it serves"
      (let [skill (:skill (body-json (request* replica-config :get "/api/describe" nil)))]
        (is (re-find #"read-only replica" skill))))))

(deftest primary-writes-pass-test
  (test-with-fresh-db "prod + marker: the same mutation goes through (recording mode permitting)"
    (mw/set-recording! true)
    (try
      (let [resp (request* primary-config :post "/api/contexts" {:title "Books" :reason "test"})
            b (body-json resp)]
        (is (= 201 (:status resp)))
        (is (integer? (:id b)))
        (is (= "Books" (:title (ds/get-item db {:id (:id b)})))))
      (finally (mw/set-recording! false)))))

(deftest dev-mode-is-unaffected-test
  (test-with-fresh-db "dev needs no marker: no guard, and writes pass as before"
    (is (false? (:read-only-replica? config/config)))
    (let [resp (request* (db-harness/app-config-with {:dev? true})
                         :post "/api/contexts" {:title "DevWrite" :reason "test"})]
      (is (= 201 (:status resp)))
      (is (= "DevWrite" (:title (ds/get-item db {:id (:id (body-json resp))})))))))

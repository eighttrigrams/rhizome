(ns rest-api.mutations-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [ring.mock.request :as mock]
            [ring.middleware.params :refer [wrap-params]]
            [datastore.config :as config]
            [rest-api :as rest-api]
            [rest-api.middleware :as mw]
            [et.vp.ds :as ds]
            [et.vp.ds.search-test :refer [reset-db with-time db]]))

(defn- ensure-embedding-column!
  []
  (jdbc/execute-one! db ["CREATE EXTENSION IF NOT EXISTS vector"])
  (jdbc/execute-one! db ["ALTER TABLE items ADD COLUMN IF NOT EXISTS embedding vector(768)"]))

(defn- with-recording-on
  [f]
  (let [was-on? (mw/enabled?)]
    (when-not was-on? (mw/toggle!))
    (try (f) (finally (when-not was-on? (mw/toggle!))))))

(use-fixtures :once
  (fn [f]
    (ensure-embedding-column!)
    (with-recording-on f)))

(def ^:private handler (delay (wrap-params (rest-api/rest-routes))))

(defn- POST*
  [path body]
  (with-redefs [config/config {:db db}]
    (@handler (-> (mock/request :post path)
                  (mock/content-type "application/json")
                  (mock/body (json/generate-string body))))))

(defn- body-json [resp] (json/parse-string (:body resp) true))

(defmacro test-with-fresh-db
  [description & body]
  `(testing ~description (reset-db) (with-time ~@body)))

(deftest create-context-minimal-test
  (test-with-fresh-db "creates a context with only :title"
    (let [resp (POST* "/rest/contexts" {:title "Books"})
          body (body-json resp)]
      (is (= 201 (:status resp)))
      (is (= "Books" (:title body)))
      (is (true? (:is-context body)))
      (is (integer? (:id body)))
      (let [stored (ds/get-item db {:id (:id body)})]
        (is (true? (:is_context stored)))
        (is (nil? (:short_title stored)))
        (is (not (true? (:hide-in-global-search (:data stored)))))))))

(deftest create-context-with-short-title-test
  (test-with-fresh-db "stores :short-title when provided"
    (let [resp (POST* "/rest/contexts" {:title "Second World War" :short-title "WW2"})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (= "WW2" (:short-title body)))
      (is (= "WW2" (:short_title stored))))))

(deftest create-context-with-hide-in-global-search-test
  (test-with-fresh-db "stores :hide-in-global-search flag in data"
    (let [resp (POST* "/rest/contexts" {:title "Hidden" :hide-in-global-search true})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (true? (:hide-in-global-search (:data stored)))))))

(deftest create-context-with-both-extras-test
  (test-with-fresh-db "applies short-title and hide-in-global-search together"
    (let [resp (POST* "/rest/contexts"
                      {:title "Private notes"
                       :short-title "PN"
                       :hide-in-global-search true})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (= "PN" (:short_title stored)))
      (is (true? (:hide-in-global-search (:data stored)))))))

(deftest create-context-with-sort-idx-test
  (test-with-fresh-db "stores :sort-idx when provided"
    (let [resp (POST* "/rest/contexts" {:title "Chapter 3" :sort-idx 3})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (= 3 (:sort_idx stored))))))

(deftest create-context-hide-false-noop-test
  (test-with-fresh-db ":hide-in-global-search=false does not write the flag"
    (let [resp (POST* "/rest/contexts" {:title "Public" :hide-in-global-search false})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (not (contains? (:data stored) :hide-in-global-search))))))

(deftest create-context-missing-title-test
  (test-with-fresh-db "rejects requests without :title"
    (let [resp (POST* "/rest/contexts" {:short-title "x"})]
      (is (= 400 (:status resp)))
      (is (= "title is required" (:error (body-json resp)))))))

(deftest create-context-recording-off-test
  (testing "with recording off, the write is dropped and no row is created"
    (reset-db)
    (with-time
      (mw/toggle!)
      (try
        (let [resp (POST* "/rest/contexts" {:title "ShouldNotPersist"})
              body (body-json resp)]
          (is (= 201 (:status resp)))
          (is (nil? (:id body)))
          (is (= "ShouldNotPersist" (:title body))))
        (finally (mw/toggle!))))))

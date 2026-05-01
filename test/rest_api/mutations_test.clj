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

(defn- PUT*
  [path body]
  (with-redefs [config/config {:db db}]
    (@handler (-> (mock/request :put path)
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
        (is (false? (:hide_in_global_search stored)))))))

(deftest create-context-with-short-title-test
  (test-with-fresh-db "stores :short-title when provided"
    (let [resp (POST* "/rest/contexts" {:title "Second World War" :short-title "WW2"})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (= "WW2" (:short-title body)))
      (is (= "WW2" (:short_title stored))))))

(deftest create-context-with-hide-in-global-search-test
  (test-with-fresh-db "stores :hide-in-global-search as a top-level column"
    (let [resp (POST* "/rest/contexts" {:title "Hidden" :hide-in-global-search true})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (true? (:hide-in-global-search body)))
      (is (true? (:hide_in_global_search stored))))))

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
      (is (true? (:hide_in_global_search stored))))))

(deftest create-context-with-sort-idx-test
  (test-with-fresh-db "stores :sort-idx when provided"
    (let [resp (POST* "/rest/contexts" {:title "Chapter 3" :sort-idx 3})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (= 3 (:sort_idx stored))))))

(deftest create-context-hide-false-noop-test
  (test-with-fresh-db ":hide-in-global-search=false leaves column at its default"
    (let [resp (POST* "/rest/contexts" {:title "Public" :hide-in-global-search false})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (false? (:hide_in_global_search stored)))
      (is (not (contains? body :hide-in-global-search))))))

(deftest create-context-missing-title-test
  (test-with-fresh-db "rejects requests without :title"
    (let [resp (POST* "/rest/contexts" {:short-title "x"})]
      (is (= 400 (:status resp)))
      (is (= "title is required" (:error (body-json resp)))))))

(deftest upsert-relation-default-show-badge-test
  (test-with-fresh-db "links source to target with show-badge defaulting to true"
    (let [ctx (ds/new-context db {:title "Books"})
          src (ds/new-item db "Source item" "src" #{(:id ctx)} 1)
          tgt (ds/new-item db "Target item" "tgt" #{(:id ctx)} 2)
          resp (PUT* "/rest/relations" {:source-id (:id src) :target-id (:id tgt)})
          stored (ds/get-item db {:id (:id src)})]
      (is (= 200 (:status resp)))
      (is (contains? (get-in stored [:data :contexts]) (:id tgt)))
      (is (true? (get-in stored [:data :contexts (:id tgt) :show-badge?]))))))

(deftest upsert-relation-show-badge-false-test
  (test-with-fresh-db "honours show-badge=false"
    (let [ctx (ds/new-context db {:title "Books"})
          src (ds/new-item db "Source" "src" #{(:id ctx)} 1)
          tgt (ds/new-item db "Target" "tgt" #{(:id ctx)} 2)
          resp (PUT* "/rest/relations"
                     {:source-id (:id src) :target-id (:id tgt) :show-badge false})
          stored (ds/get-item db {:id (:id src)})]
      (is (= 200 (:status resp)))
      (is (false? (get-in stored [:data :contexts (:id tgt) :show-badge?]))))))

(deftest upsert-relation-idempotent-test
  (test-with-fresh-db "second PUT updates show-badge for the same pair"
    (let [ctx (ds/new-context db {:title "Books"})
          src (ds/new-item db "Source" "src" #{(:id ctx)} 1)
          tgt (ds/new-item db "Target" "tgt" #{(:id ctx)} 2)]
      (PUT* "/rest/relations" {:source-id (:id src) :target-id (:id tgt) :show-badge true})
      (PUT* "/rest/relations" {:source-id (:id src) :target-id (:id tgt) :show-badge false})
      (let [stored (ds/get-item db {:id (:id src)})]
        (is (false? (get-in stored [:data :contexts (:id tgt) :show-badge?])))))))

(deftest upsert-relation-missing-ids-test
  (test-with-fresh-db "rejects missing/non-integer ids"
    (let [resp (PUT* "/rest/relations" {:source-id 1})]
      (is (= 400 (:status resp)))
      (is (= "source-id and target-id are required integers" (:error (body-json resp)))))))

(deftest upsert-relation-same-id-test
  (test-with-fresh-db "rejects source-id == target-id"
    (let [ctx (ds/new-context db {:title "Books"})
          item (ds/new-item db "X" "x" #{(:id ctx)} 1)
          resp (PUT* "/rest/relations" {:source-id (:id item) :target-id (:id item)})]
      (is (= 400 (:status resp)))
      (is (= "source-id and target-id must differ" (:error (body-json resp)))))))

(deftest upsert-relation-not-found-test
  (test-with-fresh-db "404s when source or target item does not exist"
    (let [ctx (ds/new-context db {:title "Books"})
          item (ds/new-item db "X" "x" #{(:id ctx)} 1)
          missing 9999999
          resp1 (PUT* "/rest/relations" {:source-id missing :target-id (:id item)})
          resp2 (PUT* "/rest/relations" {:source-id (:id item) :target-id missing})]
      (is (= 404 (:status resp1)))
      (is (= "source item not found" (:error (body-json resp1))))
      (is (= 404 (:status resp2)))
      (is (= "target item not found" (:error (body-json resp2)))))))

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

(deftest delete-related-items-deletes-listed-test
  (test-with-fresh-db "deletes only the items whose ids are in :item-ids"
    (let [parent (ds/new-context db {:title "Books"})
          a (ds/new-item db "Item A" "" #{(:id parent)} 1)
          b (ds/new-item db "Item B" "" #{(:id parent)} 2)
          c (ds/new-item db "Item C" "" #{(:id parent)} 3)
          resp (POST* (str "/rest/items/" (:id parent) "/related/delete")
                      {:item-ids [(:id a) (:id b)]})
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= 2 (:requested body)))
      (is (= (set [(:id a) (:id b)]) (set (:deleted body))))
      (is (empty? (:skipped body)))
      (is (empty? (:missing body)))
      (is (nil? (:id (ds/get-item db {:id (:id a)}))))
      (is (nil? (:id (ds/get-item db {:id (:id b)}))))
      (is (= (:id c) (:id (ds/get-item db {:id (:id c)})))))))

(deftest delete-related-items-empty-list-test
  (test-with-fresh-db "empty :item-ids is a no-op that returns 200"
    (let [parent (ds/new-context db {:title "Books"})
          resp (POST* (str "/rest/items/" (:id parent) "/related/delete")
                      {:item-ids []})
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= 0 (:requested body)))
      (is (= [] (:deleted body))))))

(deftest delete-related-items-missing-id-test
  (test-with-fresh-db "non-existent ids are reported as :missing, not :deleted"
    (let [parent (ds/new-context db {:title "Books"})
          a (ds/new-item db "Item A" "" #{(:id parent)} 1)
          resp (POST* (str "/rest/items/" (:id parent) "/related/delete")
                      {:item-ids [(:id a) 9999999]})
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= [(:id a)] (:deleted body)))
      (is (= [9999999] (:missing body))))))

(deftest delete-related-items-skipped-when-has-children-test
  (test-with-fresh-db "items with contained children are reported :skipped (not deleted)"
    (let [parent (ds/new-context db {:title "Library"})
          ;; mid is itself a context that contains a child, so deletion/delete-item
          ;; refuses to delete it (contained-items-count > 0)
          mid (ds/new-context db {:title "Sub-context"})
          _child (ds/new-item db "Child" "" #{(:id mid)} 1)
          ;; link mid into parent so it appears as related
          _ (ds/new-item db "Plain item" "" #{(:id parent)} 2)
          resp (POST* (str "/rest/items/" (:id parent) "/related/delete")
                      {:item-ids [(:id mid)]})
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= [] (:deleted body)))
      (is (= [(:id mid)] (:skipped body)))
      (is (= (:id mid) (:id (ds/get-item db {:id (:id mid)})))))))

(deftest delete-related-items-bad-payload-test
  (test-with-fresh-db "rejects payloads where :item-ids is missing or not a list of ints"
    (let [parent (ds/new-context db {:title "Books"})
          resp1 (POST* (str "/rest/items/" (:id parent) "/related/delete") {})
          resp2 (POST* (str "/rest/items/" (:id parent) "/related/delete")
                       {:item-ids ["not-an-int"]})]
      (is (= 400 (:status resp1)))
      (is (= 400 (:status resp2))))))

(deftest delete-related-items-parent-not-found-test
  (test-with-fresh-db "404s when the parent id has no corresponding item"
    (let [resp (POST* "/rest/items/9999999/related/delete" {:item-ids []})]
      (is (= 404 (:status resp))))))

(deftest delete-related-items-bad-parent-id-test
  (test-with-fresh-db "400s when the path id is not an integer"
    (let [resp (POST* "/rest/items/not-an-int/related/delete" {:item-ids []})]
      (is (= 400 (:status resp))))))

(deftest delete-related-items-recording-off-test
  (testing "with recording off, the request is dropped and items remain"
    (reset-db)
    (with-time
      (let [parent (ds/new-context db {:title "Books"})
            a (ds/new-item db "Item A" "" #{(:id parent)} 1)]
        (mw/toggle!)
        (try
          (let [resp (POST* (str "/rest/items/" (:id parent) "/related/delete")
                            {:item-ids [(:id a)]})
                body (body-json resp)]
            (is (= 200 (:status resp)))
            (is (true? (:dropped body)))
            (is (= [] (:deleted body)))
            (is (= (:id a) (:id (ds/get-item db {:id (:id a)})))))
          (finally (mw/toggle!)))))))

(deftest delete-related-items-not-in-describe-test
  (testing "delete-related-items is unlisted (excluded from /rest/describe)"
    (let [resp (with-redefs [config/config {:db db}]
                 (@handler (mock/request :get "/rest/describe")))
          names (set (map :name (body-json resp)))]
      (is (not (contains? names "delete-related-items"))))))

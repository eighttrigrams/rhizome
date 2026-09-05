(ns rest-api.mutations-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [db-harness]
            [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [ring.mock.request :as mock]
            [ring.middleware.params :refer [wrap-params]]
            [config :as config]
            [rest-api :as rest-api]
            [rest-api.middleware :as mw]
            [scrapers.website :as website-scraper]
            [et.vp.ds :as ds]
            [et.vp.ds.relations :as relations]
            [et.vp.ds.search-test :refer [reset-db with-time db]]))

(defn- with-recording-on
  [f]
  (mw/set-recording! true)
  (try (f) (finally (mw/set-recording! false))))

(use-fixtures :once
  (fn [f] (with-recording-on f)))

(def ^:private handler (delay (wrap-params (rest-api/rest-routes))))

(defn- with-default-reason
  "All mutations in tests get a default :reason injected (the
  wrap-require-reason middleware would otherwise reject them with 400).
  Individual tests that need to exercise the missing-reason path use
  POST-raw* / PUT-raw* below."
  [body]
  (if (and (map? body) (contains? body :reason)) body (assoc body :reason "test")))

(defn- POST*
  [path body]
  (with-redefs [config/config {:db db-harness/remote}]
    (@handler (-> (mock/request :post path)
                  (mock/content-type "application/json")
                  (mock/body (json/generate-string (with-default-reason body)))))))

(defn- PUT*
  [path body]
  (with-redefs [config/config {:db db-harness/remote}]
    (@handler (-> (mock/request :put path)
                  (mock/content-type "application/json")
                  (mock/body (json/generate-string (with-default-reason body)))))))

(defn- POST-raw*
  "POST without auto-injecting :reason — for testing the missing-reason path."
  [path body]
  (with-redefs [config/config {:db db-harness/remote}]
    (@handler (-> (mock/request :post path)
                  (mock/content-type "application/json")
                  (mock/body (json/generate-string body))))))

(defn- body-json [resp] (json/parse-string (:body resp) true))

(defmacro test-with-fresh-db
  [description & body]
  `(testing ~description (reset-db) (with-time ~@body)))

(deftest create-context-minimal-test
  (test-with-fresh-db "creates a context with only :title"
    (let [resp (POST* "/api/contexts" {:title "Books"})
          body (body-json resp)]
      (is (= 201 (:status resp)))
      (is (= "Books" (:title body)))
      (is (true? (:is-context body)))
      (is (integer? (:id body)))
      (let [stored (ds/get-item db {:id (:id body)})]
        (is (true? (:is_context stored)))
        (is (nil? (:short_title stored)))
        (is (false? (:hide_in_global_search stored)))))))

(deftest create-item-attributes-the-revision-to-the-api-test
  (test-with-fresh-db "a title-only item created over REST has an api-sourced first revision"
    (let [ctx (body-json (POST* "/api/contexts" {:title "Books"}))
          item (body-json (POST* "/api/items" {:title "Sapiens" :context-ids [(:id ctx)]}))
          {:keys [versions total]} (ds/get-description-history db {:id (:id item)})]
      (is (= 1 total))
      (is (= "api" (:source (first versions)))))))

(deftest create-context-with-short-title-test
  (test-with-fresh-db "stores :short-title when provided"
    (let [resp (POST* "/api/contexts" {:title "Second World War" :short-title "WW2"})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (= "WW2" (:short-title body)))
      (is (= "WW2" (:short_title stored))))))

(deftest create-context-with-hide-in-global-search-test
  (test-with-fresh-db "stores :hide-in-global-search as a top-level column"
    (let [resp (POST* "/api/contexts" {:title "Hidden" :hide-in-global-search true})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (true? (:hide-in-global-search body)))
      (is (true? (:hide_in_global_search stored))))))

(deftest create-context-with-both-extras-test
  (test-with-fresh-db "applies short-title and hide-in-global-search together"
    (let [resp (POST* "/api/contexts"
                      {:title "Private notes"
                       :short-title "PN"
                       :hide-in-global-search true})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (= "PN" (:short_title stored)))
      (is (true? (:hide_in_global_search stored))))))

(deftest create-context-with-human-readable-id-test
  (test-with-fresh-db "stores :human-readable-id when provided"
    (let [resp (POST* "/api/contexts" {:title "Books" :human-readable-id "books"})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (= "books" (:human-readable-id body)))
      (is (= "books" (:human_readable_id stored)))))

  (test-with-fresh-db "drops a digits-only :human-readable-id but still saves the rest"
    (let [resp (POST* "/api/contexts" {:title "Books" :human-readable-id "12345"})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (= "Books" (:title stored)))
      (is (nil? (:human_readable_id stored))))))

(deftest create-context-with-sort-idx-test
  (test-with-fresh-db "stores :sort-idx when provided"
    (let [resp (POST* "/api/contexts" {:title "Chapter 3" :sort-idx 3})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (= 3 (:sort_idx stored))))))

(deftest create-context-hide-false-noop-test
  (test-with-fresh-db ":hide-in-global-search=false leaves column at its default"
    (let [resp (POST* "/api/contexts" {:title "Public" :hide-in-global-search false})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (false? (:hide_in_global_search stored)))
      (is (not (contains? body :hide-in-global-search))))))

(deftest create-context-missing-title-test
  (test-with-fresh-db "rejects requests without :title"
    (let [resp (POST* "/api/contexts" {:short-title "x"})]
      (is (= 400 (:status resp)))
      (is (= "title is required" (:error (body-json resp)))))))

(deftest upsert-relation-default-show-badge-test
  (test-with-fresh-db "links source to target with show-badge defaulting to true"
    (let [ctx (ds/new-context db {:title "Books"})
          src (ds/new-item db "Source item" "src" #{(:id ctx)} 1)
          tgt (ds/new-item db "Target item" "tgt" #{(:id ctx)} 2)
          resp (PUT* "/api/relations" {:source-id (:id src) :target-id (:id tgt)})
          stored (ds/get-item db {:id (:id src)})]
      (is (= 200 (:status resp)))
      (is (contains? (get-in stored [:data :contexts]) (:id tgt)))
      (is (true? (get-in stored [:data :contexts (:id tgt) :show-badge?]))))))

(deftest upsert-relation-show-badge-false-test
  (test-with-fresh-db "honours show-badge=false"
    (let [ctx (ds/new-context db {:title "Books"})
          src (ds/new-item db "Source" "src" #{(:id ctx)} 1)
          tgt (ds/new-item db "Target" "tgt" #{(:id ctx)} 2)
          resp (PUT* "/api/relations"
                     {:source-id (:id src) :target-id (:id tgt) :show-badge false})
          stored (ds/get-item db {:id (:id src)})]
      (is (= 200 (:status resp)))
      (is (false? (get-in stored [:data :contexts (:id tgt) :show-badge?]))))))

(deftest upsert-relation-idempotent-test
  (test-with-fresh-db "second PUT updates show-badge for the same pair"
    (let [ctx (ds/new-context db {:title "Books"})
          src (ds/new-item db "Source" "src" #{(:id ctx)} 1)
          tgt (ds/new-item db "Target" "tgt" #{(:id ctx)} 2)]
      (PUT* "/api/relations" {:source-id (:id src) :target-id (:id tgt) :show-badge true})
      (PUT* "/api/relations" {:source-id (:id src) :target-id (:id tgt) :show-badge false})
      (let [stored (ds/get-item db {:id (:id src)})]
        (is (false? (get-in stored [:data :contexts (:id tgt) :show-badge?])))))))

(deftest upsert-relation-missing-ids-test
  (test-with-fresh-db "rejects missing/non-integer ids"
    (let [resp (PUT* "/api/relations" {:source-id 1})]
      (is (= 400 (:status resp)))
      (is (= "source-id and target-id are required integers" (:error (body-json resp)))))))

(deftest upsert-relation-same-id-test
  (test-with-fresh-db "rejects source-id == target-id"
    (let [ctx (ds/new-context db {:title "Books"})
          item (ds/new-item db "X" "x" #{(:id ctx)} 1)
          resp (PUT* "/api/relations" {:source-id (:id item) :target-id (:id item)})]
      (is (= 400 (:status resp)))
      (is (= "source-id and target-id must differ" (:error (body-json resp)))))))

(deftest upsert-relation-not-found-test
  (test-with-fresh-db "404s when source or target item does not exist"
    (let [ctx (ds/new-context db {:title "Books"})
          item (ds/new-item db "X" "x" #{(:id ctx)} 1)
          missing 9999999
          resp1 (PUT* "/api/relations" {:source-id missing :target-id (:id item)})
          resp2 (PUT* "/api/relations" {:source-id (:id item) :target-id missing})]
      (is (= 404 (:status resp1)))
      (is (= "source item not found" (:error (body-json resp1))))
      (is (= 404 (:status resp2)))
      (is (= "target item not found" (:error (body-json resp2)))))))

(deftest upsert-relation-part-of-test
  (test-with-fresh-db "marks the relation as a part-of edge and places the part"
    (let [ctx (ds/new-context db {:title "Books"})
          whole (ds/new-item db "Book" "b" #{(:id ctx)} 1)
          part (ds/new-item db "Chapter" "c" #{(:id ctx)} 2)
          resp (PUT* "/api/relations"
                     {:source-id (:id part) :target-id (:id whole)
                      :is-part-of true :part-of-sort-idx 4})]
      (is (= 200 (:status resp)))
      (let [row (jdbc/execute-one! db
                                   ["SELECT is_part_of, part_of_sort_idx FROM relations
                                     WHERE owner_id = ? AND target_id = ?"
                                    (:id whole) (:id part)])]
        (is (= 1 (:relations/is_part_of row)))
        (is (= 4 (:relations/part_of_sort_idx row))))
      (let [entry (get-in (ds/get-item db {:id (:id part)}) [:data :contexts (:id whole)])]
        (is (true? (:is-part-of? entry)) "and the items.data mirror says the same")
        (is (= 4 (:part-of-sort-idx entry))))))
  (test-with-fresh-db "leaving is-part-of out keeps the standing the relation had"
    (let [ctx (ds/new-context db {:title "Books"})
          whole (ds/new-item db "Book" "b" #{(:id ctx)} 1)
          part (ds/new-item db "Chapter" "c" #{(:id ctx)} 2)]
      (PUT* "/api/relations"
            {:source-id (:id part) :target-id (:id whole) :is-part-of true :part-of-sort-idx 4})
      (PUT* "/api/relations" {:source-id (:id part) :target-id (:id whole) :show-badge false})
      (let [entry (get-in (ds/get-item db {:id (:id part)}) [:data :contexts (:id whole)])]
        (is (true? (:is-part-of? entry)))
        (is (= 4 (:part-of-sort-idx entry))))))
  (test-with-fresh-db "rejects a non-integer sort index"
    (let [ctx (ds/new-context db {:title "Books"})
          a (ds/new-item db "A" "a" #{(:id ctx)} 1)
          b (ds/new-item db "B" "b" #{(:id ctx)} 2)
          resp (PUT* "/api/relations"
                     {:source-id (:id a) :target-id (:id b) :part-of-sort-idx "iv"})]
      (is (= 400 (:status resp)))
      (is (= "part-of-sort-idx must be an integer" (:error (body-json resp)))))))

(deftest upsert-relation-part-of-cycle-test
  (test-with-fresh-db "refuses a part-of edge that would close a loop, naming the path"
    (let [ctx (ds/new-context db {:title "Books"})
          book (ds/new-item db "Book" "" #{(:id ctx)} 1)
          chapter (ds/new-item db "Chapter" "" #{(:id ctx)} 2)]
      (PUT* "/api/relations" {:source-id (:id chapter) :target-id (:id book) :is-part-of true})
      (let [resp (PUT* "/api/relations"
                       {:source-id (:id book) :target-id (:id chapter) :is-part-of true})
            body (body-json resp)]
        (is (= 409 (:status resp)))
        (is (= (str "Refused: this would make a thing part of itself — "
                    "Chapter (" (:id chapter) ") → Book (" (:id book) ")"
                    " → Chapter (" (:id chapter) ")")
               (:error body)))
        (is (= [(:id chapter) (:id book) (:id chapter)] (:part-of-cycle body))
            "the path is in the body as ids too")
        (is (empty? (jdbc/execute! db
                                   ["SELECT id FROM relations WHERE owner_id = ? AND target_id = ?"
                                    (:id chapter) (:id book)]))
            "and nothing was written")))))

(deftest create-context-recording-off-test
  (testing "with recording off, the write is dropped and no row is created"
    (reset-db)
    (with-time
      (mw/toggle!)
      (try
        (let [resp (POST* "/api/contexts" {:title "ShouldNotPersist"})
              body (body-json resp)]
          (is (= 201 (:status resp)))
          (is (nil? (:id body)))
          (is (= "ShouldNotPersist" (:title body))))
        (finally (mw/toggle!))))))

(deftest reason-required-on-mutations-test
  (test-with-fresh-db "POST/PUT without a reason in the JSON body is rejected with 400"
    (let [r1 (POST-raw* "/api/contexts" {:title "X"})
          r2 (POST-raw* "/api/contexts" {:title "X" :reason ""})
          r3 (POST-raw* "/api/contexts" {:title "X" :reason "  "})
          r4 (POST-raw* "/api/items/1/related/delete" nil)]
      (is (= 400 (:status r1)))
      (is (= 400 (:status r2)))
      (is (= 400 (:status r3)))
      (is (= 400 (:status r4)))
      (is (re-find #"reason" (:error (body-json r1))))
      (is (re-find #"reason" (:error (body-json r4)))))))

(deftest reason-not-required-on-reads-test
  (test-with-fresh-db "GETs (read-only) are unaffected by the reason rule"
    (let [resp (with-redefs [config/config {:db db}]
                 (@handler (mock/request :get "/api/describe")))]
      (is (= 200 (:status resp))))))

(defn- POST-empty*
  [path]
  (POST* path {}))

(defn- GET*
  [path]
  (with-redefs [config/config {:db db-harness/remote}]
    (@handler (mock/request :get path))))

(defn- ids-with-status
  "Return the ids of rows in the given bucket whose :status equals `status`."
  [body bucket status]
  (mapv :id (filter #(= status (:status %)) (get body bucket))))

(defn- ids-in
  [body bucket]
  (mapv :id (get body bucket)))

(defn- find-by-id [coll id] (first (filter #(= id (:id %)) coll)))

(deftest delete-related-items-deletes-all-related-test
  (test-with-fresh-db "deletes every item related to the parent (q ignored)"
    (let [parent (ds/new-context db {:title "Books"})
          a (ds/new-item db "Item A" "" #{(:id parent)} 1)
          b (ds/new-item db "Item B" "" #{(:id parent)} 2)
          unrelated (ds/new-context db {:title "Other"})
          c (ds/new-item db "Item C" "" #{(:id unrelated)} 3)
          resp (POST-empty* (str "/api/items/" (:id parent) "/related/delete"))
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (false? (:dry-run body)))
      (is (= (set [(:id a) (:id b)]) (set (ids-with-status body :primary "deleted"))))
      (is (empty? (ids-with-status body :primary "skipped")))
      (is (= [] (:cascade body)))
      (is (nil? (:id (ds/get-item db {:id (:id a)}))))
      (is (nil? (:id (ds/get-item db {:id (:id b)}))))
      (is (= (:id c) (:id (ds/get-item db {:id (:id c)})))))))

(deftest delete-related-items-tombstones-the-texts-it-takes-test
  (test-with-fresh-db
    "the bulk delete writes down what it destroys, as a single delete does: each
     item's description and the text on each edge, as one more version, marked as
     the deletion. Both directions of an edge, because this is the gesture that can
     take a container and the thing inside it at once -- an edge can lose the end it
     runs FROM here, which no single delete can do to it."
    (jdbc/execute-one! db ["delete from history"])
    (jdbc/execute-one! db ["delete from relation_history"])
    (let [parent (ds/new-context db {:title "Library"})
          mid (ds/new-item db "Sub-context" "" #{(:id parent)} 1)
          _ (jdbc/execute-one! db ["update items set is_context = true where id = ?" (:id mid)])
          child (ds/new-item db "Child" "" #{(:id mid)} 1)
          edge-versions (fn [item-id container-id]
                          (:versions (relations/get-relation-description-history
                                       db item-id container-id)))]
      (ds/update-context-description db {:id (:id child) :description "what it said"} "app")
      (relations/update-relation-description! db (:id mid) (:id parent) "the upper edge" "app")
      (relations/update-relation-description! db (:id child) (:id mid) "the lower edge" "api")
      (is (= 200 (:status (POST-empty* (str "/api/items/" (:id parent) "/related/delete")))))
      (is (nil? (:id (ds/get-item db {:id (:id mid)}))) "precondition: both items went")
      (is (nil? (:id (ds/get-item db {:id (:id child)}))))
      (let [versions (:versions (ds/get-description-history db {:id (:id child)}))]
        (is (= "what it said" (:text (first versions)))
            "the description the cascade-deleted item was carrying")
        (is (true? (:tombstone (first versions)))))
      (let [versions (edge-versions (:id mid) (:id parent))]
        (is (= ["the upper edge"] (mapv :text versions))
            "the edge that lost the end it runs TO")
        (is (= [true] (mapv :tombstone versions))))
      (let [versions (edge-versions (:id child) (:id mid))]
        (is (= ["the lower edge"] (mapv :text versions))
            "and the edge that lost the end it runs FROM")
        (is (= ["api"] (mapv :source versions))
            "still stamped with whoever wrote it, which is what provenance rests on")
        (is (= [true] (mapv :tombstone versions)))))))

(deftest delete-related-items-no-related-test
  (test-with-fresh-db "parent with no related items returns empty buckets"
    (let [parent (ds/new-context db {:title "Books"})
          resp (POST-empty* (str "/api/items/" (:id parent) "/related/delete"))
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= [] (:primary body)))
      (is (= [] (:cascade body)))
      (is (= [] (:unlinked body))))))

(deftest delete-related-items-primary-context-still-deletes-test
  (test-with-fresh-db
    "primary that is itself a context (is_context=true with children) is deleted, and its only-via-primary children cascade-delete"
    (let [parent (ds/new-context db {:title "Library"})
          ;; mid is a sub-context with one child reachable only through mid.
          mid (ds/new-item db "Sub-context" "" #{(:id parent)} 1)
          _ (jdbc/execute-one! db ["update items set is_context = true where id = ?" (:id mid)])
          child (ds/new-item db "Child" "" #{(:id mid)} 1)
          resp (POST-empty* (str "/api/items/" (:id parent) "/related/delete"))
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= [(:id mid)] (ids-with-status body :primary "deleted")))
      (is (= [(:id child)] (ids-with-status body :cascade "deleted")))
      (is (nil? (:id (ds/get-item db {:id (:id mid)}))))
      (is (nil? (:id (ds/get-item db {:id (:id child)})))))))

(deftest delete-related-items-context-not-found-test
  (test-with-fresh-db "404s when the context id has no corresponding item"
    (let [resp (POST-empty* "/api/items/9999999/related/delete")]
      (is (= 404 (:status resp))))))

(deftest delete-related-items-bad-context-id-test
  (test-with-fresh-db "400s when the path id is not an integer"
    (let [resp (POST-empty* "/api/items/not-an-int/related/delete")]
      (is (= 400 (:status resp))))))

(deftest delete-related-items-recording-off-test
  (testing "with recording off, the request is dropped and items remain"
    (reset-db)
    (with-time
      (let [parent (ds/new-context db {:title "Books"})
            a (ds/new-item db "Item A" "" #{(:id parent)} 1)]
        (mw/toggle!)
        (try
          (let [resp (POST-empty* (str "/api/items/" (:id parent) "/related/delete"))
                body (body-json resp)]
            (is (= 200 (:status resp)))
            (is (true? (:dropped body)))
            (is (= [] (:primary body)))
            (is (= [] (:cascade body)))
            (is (= [] (:unlinked body)))
            (is (= (:id a) (:id (ds/get-item db {:id (:id a)})))))
          (finally (mw/toggle!)))))))

(deftest deletion-preview-related-items-test
  (test-with-fresh-db "preview returns the same candidates without deleting"
    (let [parent (ds/new-context db {:title "Books"})
          a (ds/new-item db "Item A" "" #{(:id parent)} 1)
          b (ds/new-item db "Item B" "" #{(:id parent)} 2)
          resp (GET* (str "/api/items/" (:id parent) "/related/deletion-preview"))
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (true? (:dry-run body)))
      (is (= (set [(:id a) (:id b)]) (set (ids-with-status body :primary "deleted"))))
      ;; nothing was actually deleted
      (is (= (:id a) (:id (ds/get-item db {:id (:id a)}))))
      (is (= (:id b) (:id (ds/get-item db {:id (:id b)})))))))

(deftest deletion-preview-bypasses-recording-mode-test
  (testing "preview runs read-only even when recording mode is off"
    (reset-db)
    (with-time
      (let [parent (ds/new-context db {:title "Books"})
            a (ds/new-item db "Item A" "" #{(:id parent)} 1)]
        (mw/toggle!)
        (try
          (let [resp (GET* (str "/api/items/" (:id parent) "/related/deletion-preview"))
                body (body-json resp)]
            (is (= 200 (:status resp)))
            (is (true? (:dry-run body)))
            (is (false? (contains? body :dropped)))
            (is (= [(:id a)] (ids-with-status body :primary "deleted")))
            (is (= (:id a) (:id (ds/get-item db {:id (:id a)})))))
          (finally (mw/toggle!)))))))

(deftest deletion-preview-and-delete-agree-test
  (test-with-fresh-db "preview returns the same buckets an actual delete would produce"
    (let [parent (ds/new-context db {:title "Library"})
          mid (ds/new-item db "Sub-context" "" #{(:id parent)} 1)
          _ (jdbc/execute-one! db ["update items set is_context = true where id = ?" (:id mid)])
          _child (ds/new-item db "Child" "" #{(:id mid)} 1)
          preview (body-json (GET* (str "/api/items/" (:id parent) "/related/deletion-preview")))
          actual (body-json (POST-empty* (str "/api/items/" (:id parent) "/related/delete")))]
      (is (= (set (ids-in preview :primary)) (set (ids-in actual :primary))))
      (is (= (set (ids-in preview :cascade)) (set (ids-in actual :cascade))))
      (is (= (set (ids-in preview :unlinked)) (set (ids-in actual :unlinked)))))))

;; -- cascade & unlink behavior -----------------------------------------------

(defn- link!
  "Add a relation owner→target (owner contains target) directly, and patch
  the target's data.contexts so it stays in sync — that's what the rest of
  the app reads. We bypass /api/relations because its implementation
  reshapes the target's contexts via a full delete+reinsert, which is more
  side-effect than these tests want to model."
  [owner-id target-id]
  (jdbc/execute! db
                 ["insert into relations (owner_id, target_id) values (?, ?)"
                  owner-id target-id])
  (let [{:items/keys [data]} (jdbc/execute-one! db ["select data from items where id = ?"
                                                    target-id])
        existing (if data (json/parse-string data true) {})
        new-data (assoc-in existing [:contexts (str owner-id)]
                           {:title (str "owner" owner-id) :show-badge? true})]
    (jdbc/execute! db
                   ["update items set data = ? where id = ?"
                    (json/generate-string new-data) target-id])))

(deftest neighbor-orphaned-and-deleted-test
  (test-with-fresh-db
    "after unlinking, a neighbor that has no other inbound and is not a context cascades-deletes"
    (let [parent (ds/new-context db {:title "A"})
          b (ds/new-item db "B" "" #{(:id parent)} 1)
          e (ds/new-item db "E" "" #{(:id parent)} 2)
          ;; b -> e (b owns e); e survives only by way of b
          _ (link! (:id b) (:id e))
          ;; unlink e from parent so its only inbound is via b
          _ (jdbc/execute! db ["delete from relations where owner_id = ? and target_id = ?"
                               (:id parent) (:id e)])
          resp (POST-empty* (str "/api/items/" (:id parent) "/related/delete"))
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (some #(= (:id b) (:id %)) (:primary body)))
      (is (= [(:id e)] (ids-with-status body :cascade "deleted")))
      (is (nil? (:id (ds/get-item db {:id (:id b)}))))
      (is (nil? (:id (ds/get-item db {:id (:id e)})))))))

(deftest neighbor-with-other-inbound-survives-test
  (test-with-fresh-db
    "neighbor with an additional inbound relation from outside the primary set is kept, with reason :has-other-inbound"
    (let [parent (ds/new-context db {:title "A"})
          other-parent (ds/new-context db {:title "X"})
          b (ds/new-item db "B" "" #{(:id parent)} 1)
          e (ds/new-item db "E" "" #{(:id parent) (:id other-parent)} 2)
          _ (link! (:id b) (:id e))
          ;; Remove the direct parent->e link so e is only connected to parent via b
          _ (jdbc/execute! db ["delete from relations where owner_id = ? and target_id = ?"
                               (:id parent) (:id e)])
          body (body-json (POST-empty* (str "/api/items/" (:id parent) "/related/delete")))
          unlinked-e (find-by-id (:unlinked body) (:id e))]
      (is (= [(:id b)] (ids-with-status body :primary "deleted")))
      (is (empty? (:cascade body)))
      (is (some? unlinked-e))
      (is (some #(= "has-other-inbound" %) (:keep-reasons unlinked-e)))
      ;; e survived
      (is (= (:id e) (:id (ds/get-item db {:id (:id e)}))))
      ;; b is gone
      (is (nil? (:id (ds/get-item db {:id (:id b)}))))
      ;; the b<->e relation is gone
      (is (empty? (jdbc/execute! db ["select * from relations where owner_id = ? and target_id = ?"
                                     (:id b) (:id e)]))))))

(deftest neighbor-is-context-flag-survives-test
  (test-with-fresh-db
    "neighbor with is_context=true is kept even when it would otherwise be orphaned"
    (let [parent (ds/new-context db {:title "A"})
          b (ds/new-item db "B" "" #{(:id parent)} 1)
          e (ds/new-item db "E" "" #{(:id parent)} 2)
          _ (jdbc/execute-one! db ["update items set is_context = true where id = ?" (:id e)])
          _ (link! (:id b) (:id e))
          _ (jdbc/execute! db ["delete from relations where owner_id = ? and target_id = ?"
                               (:id parent) (:id e)])
          body (body-json (POST-empty* (str "/api/items/" (:id parent) "/related/delete")))
          unlinked-e (find-by-id (:unlinked body) (:id e))]
      (is (some? unlinked-e))
      (is (some #(= "is-context-flag" %) (:keep-reasons unlinked-e)))
      (is (= (:id e) (:id (ds/get-item db {:id (:id e)})))))))

(deftest neighbor-with-own-children-survives-test
  (test-with-fresh-db
    "neighbor that owns other items (a context by virtue of having children) is kept, reason :has-other-children"
    (let [parent (ds/new-context db {:title "A"})
          b (ds/new-item db "B" "" #{(:id parent)} 1)
          e (ds/new-item db "E" "" #{(:id parent)} 2)
          ;; e owns its own child y
          y (ds/new-item db "Y" "" #{(:id e)} 1)
          _ (link! (:id b) (:id e))
          _ (jdbc/execute! db ["delete from relations where owner_id = ? and target_id = ?"
                               (:id parent) (:id e)])
          body (body-json (POST-empty* (str "/api/items/" (:id parent) "/related/delete")))
          unlinked-e (find-by-id (:unlinked body) (:id e))]
      (is (some? unlinked-e))
      (is (some #(= "has-other-children" %) (:keep-reasons unlinked-e)))
      (is (= (:id e) (:id (ds/get-item db {:id (:id e)}))))
      (is (= (:id y) (:id (ds/get-item db {:id (:id y)})))))))

(deftest shared-neighbor-cascade-via-two-phase-test
  (test-with-fresh-db
    "shared neighbor between two primaries is cascade-deleted only after both unlinks; order-independent"
    (let [parent (ds/new-context db {:title "A"})
          b (ds/new-item db "B" "" #{(:id parent)} 1)
          c (ds/new-item db "C" "" #{(:id parent)} 2)
          e (ds/new-item db "E" "" #{(:id parent)} 3)
          _ (link! (:id b) (:id e))
          _ (link! (:id c) (:id e))
          ;; e is only reachable via b and c (no other parent)
          _ (jdbc/execute! db ["delete from relations where owner_id = ? and target_id = ?"
                               (:id parent) (:id e)])
          body (body-json (POST-empty* (str "/api/items/" (:id parent) "/related/delete")))]
      (is (= #{(:id b) (:id c)} (set (ids-with-status body :primary "deleted"))))
      (is (= [(:id e)] (ids-with-status body :cascade "deleted")))
      (is (nil? (:id (ds/get-item db {:id (:id e)})))))))

(deftest unlink-relation-rows-fully-removed-test
  (test-with-fresh-db "every relation touching a primary is gone from the relations table after delete"
    (let [parent (ds/new-context db {:title "A"})
          other (ds/new-context db {:title "X"})
          b (ds/new-item db "B" "" #{(:id parent)} 1)
          ;; e lives outside `parent`'s view (only in `other`) so it shows up as
          ;; a neighbor, not a primary.
          e (ds/new-item db "E" "" #{(:id other)} 2)
          _ (link! (:id b) (:id e))   ; b owns e
          _ (link! (:id e) (:id b))   ; e owns b — both directions
          _ (POST-empty* (str "/api/items/" (:id parent) "/related/delete"))
          remaining (jdbc/execute! db ["select * from relations where owner_id = ? or target_id = ?"
                                       (:id b) (:id b)])]
      (is (empty? remaining))
      ;; e survives because it is still linked to `other` parent
      (is (= (:id e) (:id (ds/get-item db {:id (:id e)})))))))

(deftest unlinked-neighbor-data-contexts-cleaned-up-test
  (test-with-fresh-db
    "kept-neighbor's data.contexts no longer references the deleted primary"
    (let [parent (ds/new-context db {:title "A"})
          other (ds/new-context db {:title "X"})
          b (ds/new-item db "B" "" #{(:id parent)} 1)
          e (ds/new-item db "E" "" #{(:id parent) (:id other)} 2)
          _ (link! (:id b) (:id e))     ; b owns e — adds b to e's data.contexts
          _ (jdbc/execute! db ["delete from relations where owner_id = ? and target_id = ?"
                               (:id parent) (:id e)])
          ;; sanity-check: b is in e's data.contexts before delete
          pre-e (ds/get-item db {:id (:id e)})
          _ (is (contains? (get-in pre-e [:data :contexts]) (:id b)))
          _ (POST-empty* (str "/api/items/" (:id parent) "/related/delete"))
          post-e (ds/get-item db {:id (:id e)})]
      (is (not (contains? (get-in post-e [:data :contexts]) (:id b))))
      (is (not (contains? (get-in post-e [:data :contexts]) (str (:id b))))))))

(deftest primary-with-grandchild-recursive-cascade-test
  (test-with-fresh-db
    "primary B contains X; X has no other inbound and is not a context → X cascade-deletes"
    (let [parent (ds/new-context db {:title "A"})
          b (ds/new-item db "B" "" #{(:id parent)} 1)
          x (ds/new-item db "X" "" #{(:id b)} 1)
          body (body-json (POST-empty* (str "/api/items/" (:id parent) "/related/delete")))]
      (is (= [(:id b)] (ids-with-status body :primary "deleted")))
      (is (= [(:id x)] (ids-with-status body :cascade "deleted")))
      (is (nil? (:id (ds/get-item db {:id (:id b)}))))
      (is (nil? (:id (ds/get-item db {:id (:id x)})))))))

;; -- the import door, and what is scraped ------------------------------------

(defmacro with-gate-shut
  "Run the body with recording mode off — the state the write gate is in
   outside a UI session, and the only state in which a bypass means anything."
  [& body]
  `(do (mw/toggle!) (try ~@body (finally (mw/toggle!)))))

(defn- imports-context!
  "A context carrying the human-readable id \"imports\", made the way
   `poll/ensure-imports-context!` makes it."
  []
  (let [ctx (ds/new-context db {:title "Imports"})]
    (jdbc/execute-one! db ["update items set human_readable_id = 'imports' where id = ?" (:id ctx)])
    ctx))

(defn- titled
  [title]
  (jdbc/execute! db ["select id from items where title = ?" title]))

(deftest create-item-bypasses-the-gate-for-the-imports-context-test
  (test-with-fresh-db "an item filed under 'imports' and nothing else is written with the gate shut"
    (let [imports (imports-context!)]
      (with-gate-shut
        (let [resp (POST* "/api/items" {:title "Sapiens" :context-ids [(:id imports)]})
              body (body-json resp)]
          (is (= 201 (:status resp)))
          (is (integer? (:id body)) "a real id came back, not the {:created true} stub")
          (let [stored (ds/get-item db {:id (:id body)})]
            (is (= "Sapiens" (:title stored)))
            (is (contains? (get-in stored [:data :contexts]) (:id imports)))))))))

(deftest create-item-bypasses-the-gate-with-imports-among-the-contexts-test
  (test-with-fresh-db "'imports' among the contexts opens the door, whatever else is named"
    (let [imports (imports-context!)
          other (ds/new-context db {:title "Books"})]
      (with-gate-shut
        (let [resp (POST* "/api/items"
                          {:title "Sapiens" :context-ids [(:id imports) (:id other)]})
              body (body-json resp)]
          (is (= 201 (:status resp)))
          (is (integer? (:id body)) "a real id came back, not the {:created true} stub")
          (let [stored (ds/get-item db {:id (:id body)})]
            (is (contains? (get-in stored [:data :contexts]) (:id imports)))
            (is (contains? (get-in stored [:data :contexts]) (:id other))
                "and the context named alongside it is on the item too")))))))

(deftest create-item-does-not-bypass-when-imports-is-not-named-test
  (test-with-fresh-db "the door exists but this request does not name it — gated like any other"
    ;; The one that tells "imports is among them" apart from "a door exists":
    ;; the handle is on a context here, it is just not one this write asked for.
    (let [_ (imports-context!)
          other (ds/new-context db {:title "Books"})]
      (with-gate-shut
        (let [resp (POST* "/api/items" {:title "Sapiens" :context-ids [(:id other)]})]
          (is (= 201 (:status resp)))
          (is (= {:created true} (body-json resp)))
          (is (empty? (titled "Sapiens"))))))))

(deftest create-item-does-not-bypass-without-the-imports-context-test
  (test-with-fresh-db "a context merely titled Imports is not the door — the handle is"
    ;; Same title, no human_readable_id. If the lookup ever went by title this
    ;; would be the write that slipped through.
    (let [ctx (ds/new-context db {:title "Imports"})]
      (with-gate-shut
        (let [resp (POST* "/api/items" {:title "Sapiens" :context-ids [(:id ctx)]})]
          (is (= 201 (:status resp)))
          (is (= {:created true} (body-json resp)))
          (is (empty? (titled "Sapiens"))))))))

;; -- the door on PUT /api/items/:id ------------------------------------------

(deftest update-description-bypasses-the-gate-for-an-empty-description-test
  (test-with-fresh-db "an item with no description yet can be written to with the gate shut"
    (let [imports (imports-context!)
          books (ds/new-context db {:title "Books"})
          item (ds/new-item db "Sapiens" "" #{(:id books)} nil "api")]
      (with-gate-shut
        (let [resp (PUT* (str "/api/items/" (:id item)) {:description "a history of us"})
              stored (ds/get-item db {:id (:id item)})]
          (is (= 200 (:status resp)))
          (is (= "a history of us" (:description stored)))
          (is (contains? (get-in stored [:data :contexts]) (:id imports))
              "and what came in through the door is filed where such things go")
          (is (contains? (get-in stored [:data :contexts]) (:id books))
              "alongside where it already was"))))))

(deftest update-description-refuses-over-an-existing-description-test
  (test-with-fresh-db "an item that already has a description is refused, out loud"
    ;; The half that makes the door an add and not an overwrite. Without it a
    ;; caller could replace any text in the graph with the gate shut. And it is
    ;; a refusal rather than a drop, because no amount of sending it again from
    ;; out here will make it land, and a caller cannot learn that from a stub.
    (let [imports (imports-context!)
          books (ds/new-context db {:title "Books"})
          item (ds/new-item db "Sapiens" "" #{(:id books)} nil "api")
          _ (ds/update-context-description db {:id (:id item) :description "mine"} "app")]
      (with-gate-shut
        (let [resp (PUT* (str "/api/items/" (:id item)) {:description "not mine"})
              body (body-json resp)
              stored (ds/get-item db {:id (:id item)})]
          (is (= 409 (:status resp)))
          (is (true? (:collision body)))
          (is (= (:id item) (:item-id body)))
          (is (re-find #"already has a description" (:error body)))
          (is (= "mine" (:description stored)) "and the text that was there is still there")
          (is (not (contains? (get-in stored [:data :contexts]) (:id imports)))))))))

(deftest update-description-with-the-gate-open-replaces-an-existing-one-test
  (test-with-fresh-db "recording on, and replacing a description is an ordinary write"
    ;; The refusal above is about the door, not about the endpoint. With the
    ;; gate open this is the owner replacing his own text and goes through.
    (let [books (ds/new-context db {:title "Books"})
          item (ds/new-item db "Sapiens" "" #{(:id books)} nil "api")
          _ (ds/update-context-description db {:id (:id item) :description "mine"} "app")
          resp (PUT* (str "/api/items/" (:id item)) {:description "second thoughts"})]
      (is (= 200 (:status resp)))
      (is (= "second thoughts" (:description (ds/get-item db {:id (:id item)})))))))

(deftest update-description-drops-without-the-imports-context-test
  (test-with-fresh-db "no context carries the handle, so there is no door — and no refusal"
    ;; Gated, not refused: there is nothing here to displace, and the day a
    ;; context takes the handle this same request goes through. That is a shut
    ;; gate and not a standing no, so it gets the stub the gate always gave.
    (let [books (ds/new-context db {:title "Books"})
          item (ds/new-item db "Sapiens" "" #{(:id books)} nil "api")]
      (with-gate-shut
        (let [resp (PUT* (str "/api/items/" (:id item)) {:description "a history of us"})
              stored (ds/get-item db {:id (:id item)})]
          (is (= 200 (:status resp)))
          (is (nil? (:description stored)) "nothing was written"))))))

(deftest update-description-with-the-gate-open-does-not-file-under-imports-test
  (test-with-fresh-db "recording on: the description is replaced and nothing else happens"
    ;; The contrast the spec draws. Filing under Imports says "this came from
    ;; outside"; a write the owner made with the gate open did not.
    (let [imports (imports-context!)
          books (ds/new-context db {:title "Books"})
          item (ds/new-item db "Sapiens" "" #{(:id books)} nil "api")
          resp (PUT* (str "/api/items/" (:id item)) {:description "a history of us"})
          stored (ds/get-item db {:id (:id item)})]
      (is (= 200 (:status resp)))
      (is (= "a history of us" (:description stored)))
      (is (not (contains? (get-in stored [:data :contexts]) (:id imports)))
          "an open gate is the owner's own hand — it does not announce itself to Imports"))))

(deftest create-item-without-scrape-stores-a-url-title-as-is-test
  (test-with-fresh-db "with no scrape param a URL-shaped title is stored verbatim, stamped api"
    (let [ctx (ds/new-context db {:title "Books"})
          resp (POST* "/api/items" {:title "https://example.com/some/page"
                                    :context-ids [(:id ctx)]})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (= "https://example.com/some/page" (:title stored))
          "the ingesters never saw it — a scrape would have put the page's title here")
      (is (= "api" (:description_source stored)))
      (is (= "api" (:source (first (:versions (ds/get-description-history db {:id (:id body)})))))))))

(deftest create-item-with-scrape-runs-the-ingesters-test
  (test-with-fresh-db "?scrape=true hands a URL title to the ingesters, and what they store is scraper"
    (let [ctx (ds/new-context db {:title "Books"})
          _ (ds/new-context db {:title "Websites"})]
      ;; The box has no network. Only the fetch is stubbed; the dispatch, the
      ;; website ingester and the provenance stamp are the real ones.
      (with-redefs [website-scraper/get-metadata (fn [url] {:title (str "Page at " url) :image nil})]
        (let [resp (POST* "/api/items?scrape=true"
                          {:title "https://example.com/some/page" :context-ids [(:id ctx)]})
              body (body-json resp)
              stored (ds/get-item db {:id (:id body)})]
          (is (= 201 (:status resp)))
          (is (= "Page at https://example.com/some/page" (:title stored)))
          (is (= "scraper" (:description_source stored))))))))

(deftest create-item-with-scrape-keeps-the-named-contexts-test
  (test-with-fresh-db "a bare-domain URL is filed under the contexts the request named"
    ;; The domain has no path, so the website ingester's own item *is* the item
    ;; being created. It used to be conjured a few lines above the branch that
    ;; files it, and so came out under Websites alone — the import door opened
    ;; on a context the item then never reached.
    (let [imports (imports-context!)
          _ (ds/new-context db {:title "Websites"})]
      (with-redefs [website-scraper/get-metadata (fn [url] {:title (str "Site " url) :image nil})]
        (with-gate-shut
          (let [resp (POST* "/api/items?scrape=true"
                            {:title "https://example.com" :context-ids [(:id imports)]})
                body (body-json resp)
                stored (ds/get-item db {:id (:id body)})]
            (is (= 201 (:status resp)))
            (is (contains? (get-in stored [:data :contexts]) (:id imports)))))))))

(deftest create-item-with-scrape-refuses-a-url-already-held-test
  (test-with-fresh-db "a URL the graph already holds is refused, and nothing about it changes"
    ;; POST creates. A second bookmark of the same page is a collision, and the
    ;; refusal has to leave the item exactly as it stood — the description on
    ;; this request is the one that would otherwise have overwritten it, and the
    ;; context on this request is the filing that would otherwise have happened.
    (let [imports (imports-context!)
          books (ds/new-context db {:title "Books"})
          _ (ds/new-context db {:title "Websites"})]
      (with-redefs [website-scraper/get-metadata (fn [url] {:title (str "Page at " url) :image nil})]
        (let [first-id (:id (body-json (POST* "/api/items?scrape=true"
                                              {:title "https://example.com/some/page"
                                               :context-ids [(:id books)]
                                               :description "what I thought of it"})))]
          (with-gate-shut
            (let [resp (POST* "/api/items?scrape=true"
                              {:title "https://example.com/some/page"
                               :context-ids [(:id imports)]
                               :description "something else entirely"})
                  body (body-json resp)
                  stored (ds/get-item db {:id first-id})]
              (is (= 409 (:status resp)))
              (is (true? (:collision body)))
              (is (= first-id (:existing-item-id body)) "the refusal names what it collided with")
              (is (re-find #"already in the graph" (:error body)))
              (is (= "what I thought of it" (:description stored))
                  "the description on the refused request did not land")
              (is (not (contains? (get-in stored [:data :contexts]) (:id imports)))
                  "and neither did the context it named")
              (is (contains? (get-in stored [:data :contexts]) (:id books))
                  "what was already there is untouched"))))))))

(deftest create-item-with-scrape-refuses-a-known-post-from-another-site-test
  (test-with-fresh-db "a collision on another site is the same refusal, not a 500"
    ;; The x.com ingester threw on a post already in the graph, so a bookmark
    ;; application re-posting a link read a 500 and a stack trace. The collision
    ;; is a fact about the graph, not a failure, and every site says so the same
    ;; way. This one reaches no network: the ingester builds the post from the URL.
    (let [imports (imports-context!)
          books (ds/new-context db {:title "Books"})
          _ (ds/new-context db {:title "Twitter"})
          _ (ds/new-context db {:title "Twitter Handles"})
          _ (ds/new-context db {:title "Poasts"})
          url "https://x.com/someone/status/123"
          first-id (:id (body-json (POST* "/api/items?scrape=true"
                                          {:title url :context-ids [(:id books)]})))]
      (is (integer? first-id) "the first bookmark landed")
      (with-gate-shut
        (let [resp (POST* "/api/items?scrape=true" {:title url :context-ids [(:id imports)]})
              body (body-json resp)]
          (is (= 409 (:status resp)))
          (is (= first-id (:existing-item-id body)))
          (is (= 1 (count (titled "X Post"))) "the post was not stored a second time"))))))

(deftest create-item-with-scrape-but-no-ingester-is-still-api-test
  (test-with-fresh-db "?scrape=true on a title no ingester claims is stored as it came in, stamped api"
    (let [ctx (ds/new-context db {:title "Books"})
          resp (POST* "/api/items?scrape=true" {:title "Sapiens" :context-ids [(:id ctx)]})
          body (body-json resp)
          stored (ds/get-item db {:id (:id body)})]
      (is (= 201 (:status resp)))
      (is (= "Sapiens" (:title stored)))
      (is (= "api" (:description_source stored))
          "asking for a scrape is not the same as having been scraped"))))

(deftest delete-related-items-not-in-describe-test
  (testing "delete-related-items + deletion-preview are unlisted in /api/describe"
    (let [resp (with-redefs [config/config {:db db}]
                 (@handler (mock/request :get "/api/describe")))
          endpoints (:endpoints (body-json resp))
          names (set (map :name endpoints))]
      (is (not (contains? names "delete-related-items")))
      (is (not (contains? names "deletion-preview-related-items"))))))

(ns api.harness-wiring-test
  "That the harness switch is real, and what it is.

   Everything else in `test/integration/api` and `test/integration/rest_api`
   now goes through a db-server without saying so -- the switch was made in
   setup and no test body knows. That is the point, and it is also why the
   arrangement needs one place that states it and checks it, or a regression to
   a plain DataSource would be invisible: every one of those suites would go on
   passing, and the chain they are supposed to be exercising would be gone."
  (:require [api.harness :as harness]
            [api.helpers :refer [with-fresh-db]]
            [clojure.test :refer [deftest is testing]]
            [db :as db]
            [db-harness]
            [et.vp.ds.search-test :refer [db]]
            [next.jdbc :as jdbc]))

(deftest what-the-harness-hands-the-app-is-a-db-server-over-http
  (is (db/remote? db-harness/remote)
      "a map naming a db-server, not a DataSource")
  (testing "and it is genuinely used, not merely configured"
    ;; Point the harness at nothing and a dispatched call cannot reach a
    ;; database. A regression that quietly handed the app a local DataSource
    ;; would sail past this, and past nothing else in the suite.
    (with-redefs [db-harness/remote {:db-server/url "http://127.0.0.1:9"}]
      (is (thrown? Exception (harness/call! :insert-context nil {:title "Nowhere"}))))))

(deftest two-names-one-database
  ;; The whole arrangement in one test: a write that entered through the app and
  ;; left this process over HTTP, read back through the DataSource the tests use
  ;; on themselves. If these were two databases, the row would not be here.
  (with-fresh-db "a write over the wire is visible to the local handle"
    (let [ctx (:selected-item (harness/call! :insert-context nil {:title "Written over the wire"}))
          row (jdbc/execute-one! db ["SELECT title FROM items WHERE id = ?" (:id ctx)])]
      (is (= "Written over the wire" (:items/title row))
          "one database, reached by two names"))))

(deftest the-tests-own-handle-is-still-local
  ;; The other half of the decision, and the reason no test body had to change:
  ;; `search-test/db` is the DataSource it always was. 88 statements across 19
  ;; files depend on that.
  (is (not (db/remote? db)))
  (is (instance? javax.sql.DataSource db)))

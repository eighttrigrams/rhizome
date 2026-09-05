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
  (testing "the /ui half: what call! injects as the dispatcher's server-args"
    (is (db/remote? db-harness/remote)
        "a map naming a db-server, not a DataSource"))
  (testing "the REST half: what the handlers get as config/config"
    ;; The half this test used to miss entirely. Every REST suite builds its
    ;; handler config out of `db-harness/app-config`, so there is one thing to
    ;; assert about and reverting the switch cannot hide in one of seven files.
    (is (db/remote? (:db db-harness/app-config)))
    (is (db/remote? (:db (db-harness/app-config-with {:folders {} :dev? true})))
        "and adding to it does not lose it")))

(deftest the-remote-handle-is-used-and-not-merely-configured
  ;; Both directions in one test, because either alone proves little. That the
  ;; call fails when the db-server is not there says the handle is really being
  ;; dialled; that the same call succeeds against the real one says the failure
  ;; was about the wire and not about the command being wrong. `thrown?` on its
  ;; own would be satisfied by a typo in the command name.
  (with-fresh-db "insert-context, against nothing and against the db-server"
    (testing "pointed at a port with nothing behind it"
      (let [thrown (with-redefs [db-harness/remote {:db-server/url "http://127.0.0.1:9"}]
                     (try (harness/call! :insert-context nil {:title "Nowhere"})
                          nil
                          (catch Throwable t t)))]
        (is (some? thrown))
        (is (re-find #"(?i)connection refused" (str (.getMessage thrown)))
            (str "the failure has to be the wire, not the command; got: "
                 (.getMessage thrown)))))
    (testing "and the very same call against the db-server that is there"
      (let [ctx (:selected-item (harness/call! :insert-context nil {:title "Somewhere"}))]
        (is (= "Somewhere" (:title ctx))
            ":insert-context is a live command, so the refusal above meant what it said")))))

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

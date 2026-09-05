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
            [next.jdbc :as jdbc]
            [rest-api.item-images-test :as item-images-test]
            [rest-api.mutations-test :as mutations-test]
            [rest-api.queries-test :as queries-test]
            [rest-api.replica-test :as replica-test]))

(def ^:private dead-server
  "A port with nothing behind it. Port 9 is discard; nothing listens on it here."
  {:db-server/url "http://127.0.0.1:9"})

(deftest what-the-harness-hands-the-app-is-a-db-server-over-http
  ;; What the two halves are *given*. That they use it is the next two tests:
  ;; this one alone would be satisfied by a definition nobody reads.
  (testing "the /ui half: what call! injects as the dispatcher's server-args"
    (is (db/remote? db-harness/remote)
        "a map naming a db-server, not a DataSource"))
  (testing "the REST half: what the handlers get as config/config"
    (is (db/remote? (:db (db-harness/app-config))))
    (is (db/remote? (:db (db-harness/app-config {:folders {} :dev? true})))
        "and adding to it does not lose it")))

(deftest the-remote-handle-is-used-and-not-merely-configured
  ;; Both directions in one test, because either alone proves little. That the
  ;; call fails when the db-server is not there says the handle is really being
  ;; dialled; that the same call succeeds against the real one says the failure
  ;; was about the wire and not about the command being wrong. `thrown?` on its
  ;; own would be satisfied by a typo in the command name.
  (with-fresh-db "insert-context, against nothing and against the db-server"
    (testing "pointed at a port with nothing behind it"
      (let [thrown (with-redefs [db-harness/remote dead-server]
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

(def ^:private rest-request-helpers
  "The same read -- `GET /api/contexts` -- sent through each REST suite's own
   request helper. Private vars, reached with `#'` on purpose: what is under
   test is the config those helpers really build, not a copy of it made here.

   Four entries because there are exactly four such helpers, one per suite, and
   every handler any of those files stands up goes through one of them -- bar
   the two named exceptions in `mutations_test`, which build config inline in a
   test body and call only `GET /api/describe`, introspection over `ns-publics`
   with no statement of any kind behind it.

   A GET, so that neither the reason rule nor the replica write gate stands
   between the call and the handle; and one that reads the database, so a handle
   that cannot be reached shows up as a failure rather than as a cheerful 200."
  [["rest-api.queries-test/GET*"
    #(#'queries-test/GET* "/api/contexts")]
   ["rest-api.mutations-test/GET*, and with it POST*, PUT*, POST-raw*"
    #(#'mutations-test/GET* "/api/contexts")]
   ["rest-api.item-images-test/GET*"
    #(#'item-images-test/GET* {} "/api/contexts")]
   ["rest-api.replica-test/request*"
    #(#'replica-test/request* @#'replica-test/replica-config
                              :get "/api/contexts" nil)]])

(deftest the-rest-helpers-really-dial-the-db-server
  ;; The REST half of the test above, in the same shape and for the same
  ;; reason.
  ;;
  ;; `(db/remote? (:db (db-harness/app-config)))` says the definition is
  ;; remote. It does not say that any handler is ever given it, and the
  ;; difference is not academic: with all seven of the old `{:db db}` literals
  ;; put back, the whole suite -- this file included -- stayed green, because
  ;; nothing here reached the helpers that read the definition. That made the
  ;; consolidation a convention. This is what makes it a guard: point the
  ;; handle at a dead port, and a helper that quietly went back to the local
  ;; DataSource answers 200 where it has to fail on the wire.
  (with-fresh-db "every REST suite's own helper, against nothing and against the db-server"
    (doseq [[helper call] rest-request-helpers]
      (testing helper
        (let [dead (with-redefs [db-harness/remote dead-server]
                     (call))]
          (is (= 500 (:status dead))
              "a handler whose db-server is not there cannot answer a read")
          (is (re-find #"(?i)connection refused" (str (:body dead)))
              (str "the failure has to be the wire, not the command; got: "
                   (:body dead))))
        (let [live (call)]
          (is (= 200 (:status live))
              "and the very same read against the db-server that is there"))))))

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

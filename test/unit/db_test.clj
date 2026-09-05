(ns db-test
  "The facade's own inventions -- what `db` does that next.jdbc does not, and
   which therefore has no coverage anywhere else in the suite.

   Three things live here, and each of them is a rule step 2 has to carry over
   the wire rather than a behaviour it inherits:

   - the statement-option whitelist and the builder lookup. The db-server's
     `/execute` handler has to honour exactly the keys named here; a key it
     forgets is not an error over there, it is a statement that quietly runs
     with different options.
   - `with-transaction`'s refusal of any binding but `[sym handle]`, so that an
     option the seam cannot carry is a compile error and not a silent drop.
   - the nested-transaction prohibition -- and the evidence that switching it on
     changed nothing about the transaction paths the app actually has today."
  (:require [clojure.test :refer [deftest is testing]]
            [datastore.connection :as connection]
            [db :as db]
            [et.vp.ds :as ds]
            [et.vp.ds.relations :as relations]
            [et.vp.ds.search-test :as search-test]
            [next.jdbc.transaction :as jdbc-tx]
            [repository.deletion :as deletion]))

(defn- with-probe-db
  "Call `f` with a handle on a throwaway file database holding one table,
   `t (n INTEGER)`. Its own database and not the suite's: these tests commit
   and roll back on purpose, and nothing else should have to know."
  [f]
  (let [file (doto (java.io.File/createTempFile "rhizome-db-facade-test" ".db")
               (.deleteOnExit))
        handle (connection/make-datasource {:dbname (.getAbsolutePath file)})]
    (db/execute-one! handle ["CREATE TABLE t (n INTEGER)"])
    (f handle)))

(defn- numbers [handle] (mapv :t/n (db/execute! handle ["SELECT n FROM t ORDER BY n"])))

;; -- the statement options -------------------------------------------------

(deftest the-option-whitelist-is-the-two-keys-step-two-must-honour
  (is (= #{:builder :return-keys} @#'db/option-keys)
      (str "This set is the contract with the db-server's /execute handler. "
           "Widening it here without widening it there is the failure this "
           "assertion exists to make loud: 22 call sites pass :return-keys, "
           "and an option dropped on the wire changes what a statement does "
           "without raising anything.")))

(deftest an-option-off-the-whitelist-is-refused-rather-than-passed-along
  (with-probe-db
    (fn [handle]
      (testing "an option next.jdbc understands and the wire has nowhere to put"
        (let [e (try (db/execute! handle ["SELECT 1"] {:timeout 5})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) "it would work perfectly here, and nowhere else")
          (is (re-find #"unsupported statement option" (.getMessage e)))
          (is (= #{:builder :return-keys} (:supported (ex-data e))))))
      (testing "execute-one! refuses it too, and not only execute!"
        (is (thrown? clojure.lang.ExceptionInfo
                     (db/execute-one! handle ["SELECT 1"] {:concurrency :updatable}))))
      (testing ":return-keys is on the list, so it goes through"
        (is (= [] (db/execute! handle ["SELECT n FROM t"] {:return-keys true})))))))

(deftest the-builder-is-looked-up-by-name-because-a-name-is-what-travels
  (with-probe-db
    (fn [handle]
      (db/execute-one! handle ["INSERT INTO t (n) VALUES (7)"])
      (testing "the default builder qualifies the key with the table, and keeps its case"
        (is (= #:t{:MiXeD 7} (db/execute-one! handle ["SELECT n AS MiXeD FROM t"]))))
      (testing ":unqualified-lower does both halves of what it is called"
        (is (= {:mixed 7}
               (db/execute-one! handle ["SELECT n AS MiXeD FROM t"]
                                {:builder :unqualified-lower}))))
      (testing "a builder name that is not on the list is refused, not ignored"
        (let [e (try (db/execute! handle ["SELECT n FROM t"] {:builder :qualified-kebab})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (re-find #"unknown result-set builder" (.getMessage e)))
          (is (= #{:unqualified-lower} (:supported (ex-data e)))))))))

;; -- what with-transaction will and will not take --------------------------

(defn- expansion-failure
  "The exception thrown while macroexpanding `form`, or nil when it expanded.
   `macroexpand-1` wraps whatever a macro throws in a CompilerException, so
   what is worth asserting on is the cause underneath it."
  [form]
  (try (macroexpand-1 form)
       nil
       (catch Throwable t (or (ex-cause t) t))))

(deftest with-transaction-refuses-a-binding-it-would-have-to-ignore
  (testing "next.jdbc's third element, the transaction options"
    (let [e (expansion-failure '(db/with-transaction [tx handle {:rollback-only true}] :body))]
      (is (instance? clojure.lang.ExceptionInfo e)
          "taking :rollback-only and dropping it would turn a rollback into a commit")
      (is (re-find #"takes no transaction options" (.getMessage e)))
      (is (= [{:rollback-only true}] (:options (ex-data e))))))
  (testing "a binding with no handle in it, which used to expand to a nil one"
    (let [e (expansion-failure '(db/with-transaction [tx] :body))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (re-find #"exactly two forms" (.getMessage e)))))
  (testing "and something that is not a binding vector at all"
    (is (instance? clojure.lang.ExceptionInfo
                   (expansion-failure '(db/with-transaction "handle" :body)))))
  (testing "the two-form binding it does take, expanding into transact"
    (is (= '(db/transact handle (clojure.core/fn [tx] :body))
           (macroexpand-1 '(db/with-transaction [tx handle] :body))))))

;; -- the nested-transaction prohibition ------------------------------------

(deftest a-transaction-commits-and-a-failed-one-leaves-nothing-behind
  (with-probe-db
    (fn [handle]
      (db/with-transaction [tx handle]
        (db/execute-one! tx ["INSERT INTO t (n) VALUES (1)"]))
      (is (= [1] (numbers handle)) "it committed")
      (is (thrown? clojure.lang.ExceptionInfo
                   (db/with-transaction [tx handle]
                     (db/execute-one! tx ["INSERT INTO t (n) VALUES (2)"])
                     (throw (ex-info "the body failed" {})))))
      (is (= [1] (numbers handle)) "and the one that threw rolled back"))))

;; Under next.jdbc's default (`*nested-tx*` `:allow`) this is the shape of a
;; partial commit that raises nothing: the outer transaction is committed when
;; the INNER one ends, so the row written before the nesting survives an outer
;; transaction that goes on to fail. `db/transact` binds `:prohibit` so the
;; second transaction is an exception instead.
(deftest a-nested-transaction-is-refused-rather-than-committing-the-outer-one
  (with-probe-db
    (fn [handle]
      (is (thrown? IllegalStateException
                   (db/with-transaction [outer handle]
                     (db/execute-one! outer ["INSERT INTO t (n) VALUES (1)"])
                     (db/with-transaction [inner outer]
                       (db/execute-one! inner ["INSERT INTO t (n) VALUES (2)"])))))
      (is (= [] (numbers handle))
          "and because it threw instead of committing, the outer one rolled back"))))

(deftest the-prohibition-is-scoped-to-transact-and-not-set-globally
  (is (= :allow jdbc-tx/*nested-tx*)
      "next.jdbc's own default is left alone everywhere outside a db transaction"))

;; -- and switching it on changed nothing -----------------------------------

;; Every entry point in the app that opens a transaction, driven once. The
;; prohibition above is unconditional, so a path that nests throws here rather
;; than half-writing somewhere else; this names them, so that the day one
;; starts nesting the failing test says what the problem is.
;;
;; Seven of the eight `db/with-transaction` sites are reached from here. The
;; eighth, `semsearch.backfill/store-embedding!`, writes to items_vec and so
;; needs the sqlite-vec extension; it is driven several times per test by
;; semsearch.threshold-query-test, which is tagged ^:vector, and duplicating it
;; here would only add a test that cannot run without the dylib either.
(deftest no-transaction-path-the-app-has-today-nests
  (search-test/test-with-reset-db-and-time "every transaction the app can open, opened once"
    (let [handle search-test/db
          ;; helpers/insert-and-get-id!, twice over
          book (ds/new-context handle {:title "Book"})
          shelf (ds/new-context handle {:title "Shelf"})
          chapter (ds/new-item handle "Chapter" "" #{(:id book)} nil)]
      (is (some? (:id chapter)) "new-context and new-item, which both go through insert-and-get-id!")

      (relations/set-the-containers-of-item! handle
                                             chapter
                                             {(:id book) {:title "Book"
                                                          :show-badge? true
                                                          :is-context? true
                                                          :is-part-of? true
                                                          :part-of-sort-idx 1}}
                                             false)
      (relations/link-item-to-another-item! handle
                                            (ds/get-item handle {:id (:id chapter)})
                                            shelf
                                            true)
      (is (true? (relations/update-relation-standing! handle
                                                      (:id chapter)
                                                      (:id book)
                                                      {:show-badge? false})))
      (is (true? (relations/update-relation-description! handle
                                                         (:id chapter)
                                                         (:id book)
                                                         "why it is in here")))
      (is (true? (relations/unlink-item-from-another-item!
                   handle
                   (ds/get-item handle {:id (:id chapter)})
                   shelf))
          "it still has Book, so this one is allowed to go through")

      (deletion/plan-and-execute! handle
                                  [(ds/get-item handle {:id (:id chapter)})]
                                  false
                                  (:id book))
      (is (nil? (:id (ds/get-item handle {:id (:id chapter)})))
          "repository.deletion/execute!, the last of the seven"))))

(ns datastore.connection-concurrency-test
  "Regression guard: borrowing connections concurrently must not lose the
   sqlite-vec extension. See datastore.connection/vec-loading-datasource.
   Tagged ^:vector -- without the extension there is nothing to fail to load."
  (:require [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [config :as config]
            [datastore.schema :as schema]))

(def ^:private thread-count 16)
(def ^:private borrows-per-thread 40)

(defn- vec-ok?
  [db]
  (try (jdbc/execute-one! db ["SELECT count(*) n FROM items_vec"])
       true
       (catch Exception _ false)))

(deftest ^:vector vec-extension-survives-concurrent-connection-borrowing
  (let [db (:db config/config)]
    (schema/apply-schema! db)
    (is (vec-ok? db) "vec extension loads on a single-threaded borrow")
    (let [failures (atom 0)]
      (run! deref
            (doall (for [_ (range thread-count)]
                     (future (dotimes [_ borrows-per-thread]
                               (when-not (vec-ok? db) (swap! failures inc)))))))
      (is (zero? @failures)
          (str "items_vec query failed on " @failures " of "
               (* thread-count borrows-per-thread) " concurrent borrows")))
    (is (vec-ok? db)
        "vec extension still loads after concurrent borrowing")))

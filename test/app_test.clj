(ns app-test
  (:require [clojure.test :refer [deftest testing is]]
            [next.jdbc :as jdbc]
            datastore))

(def db {:dbtype   "postgresql"
         :dbname   "cometoid_test"
         :user     "daniel"
         :password "abcdef"
         :port     5437
         :hostname "127.0.0.1"})

(defn reset-db []
  (jdbc/execute-one! db ["delete from events"])
  (jdbc/execute-one! db ["delete from issue_issue"])
  (jdbc/execute-one! db ["delete from context_issue"])
  (jdbc/execute-one! db ["delete from context_context"])
  (jdbc/execute-one! db ["delete from texts"])
  (jdbc/execute-one! db ["delete from contexts"])
  (jdbc/execute-one! db ["delete from issues"]))

(deftest database
  (testing "base case"
    (reset-db)
    (let [id (:id (datastore/new-context db {:title "abc"}))]
      (is (= "abc" (:title (datastore/get-context db {:id id})))))))

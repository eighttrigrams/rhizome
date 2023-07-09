(ns app-test
  (:require [clojure.test :refer [deftest testing is]]
            [next.jdbc :as jdbc]
            datastore
            repository))

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

(deftest repository 
  (testing "base case"
    (reset-db)
    (let [context
          (:selected-context (repository/list-resources {:cmd :insert-context
                                                         :arg {:title "abc"}} db))]
      (is (= 
           "abc"
           (:title (:selected-context (repository/list-resources {:cmd :fetch-context
                                                                  :arg [context false]} db)))))))
  (testing "update a context"
    (reset-db)
    (let [context (select-keys (:selected-context (repository/list-resources {:cmd :insert-context
                                                                              :arg {:title "abc"}} db))
                               [:title :id])
          _ (repository/list-resources 
             {:cmd :update-context
              :arg {:context (assoc context 
                                    :title "abc1"
                                    :data {:a ["1" "2"]})}} db)
          context (first (:contexts (repository/list-resources {:active-search :contexts
                                                                 :q             ""} db)))]
      (is (=
           {:a ["1" "2"]}
           (:data (:selected-context (repository/list-resources {:cmd :fetch-context
                                                                  :arg [context false]} db))))))))

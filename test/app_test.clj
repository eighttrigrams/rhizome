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

(defn- create-context [title]
  (:selected-context 
   (repository/list-resources {:cmd :insert-context
                               :arg {:title title}} db)))

(defn- update-context [context]
  (repository/list-resources
   {:cmd :update-context
    :arg {:context context}} db))

(defn- create-issue [title context-id selected-secondary-contexts-ids]
  (:selected-issue 
   (repository/list-resources 
    {:cmd              :insert-issue
     :arg              {:title title}
     :selected-context {:id   context-id
                        :data {:selected-secondary-contexts 
                               selected-secondary-contexts-ids}}} db)))

(deftest repository 
  (testing "base case"
    (reset-db)
    (let [context (create-context "abc")]
      (is (= 
           "abc"
           (:title (:selected-context (repository/list-resources {:cmd :fetch-context
                                                                  :arg [context false]} db)))))))
  (testing "update a context"
    (reset-db)
    (let [context (select-keys (create-context "abc")
                               [:title :id])
          _ (update-context (assoc context
                                   :title "abc1"
                                   :data {:a ["1" "2"]}))
          context (first (:contexts (repository/list-resources {:active-search :contexts
                                                                :q             ""} db)))]
      (is (=
           {:a ["1" "2"]}
           (:data (:selected-context (repository/list-resources {:cmd :fetch-context
                                                                  :arg [context false]} db))))))))

(deftest search 
  (testing "aggregating contexts"
    (reset-db)
    (let [context-1    (create-context "context-1")
          context-2-id (:id (create-context "context-2"))
          context-3-id (:id (create-context "context-3"))
          context-4-id (:id (create-context "context-4"))]
      (update-context (assoc context-1 :data
                             {:highlighted-secondary-contexts [(str context-4-id)
                                                               (str context-3-id)]}))
      (:id (create-issue "issue-1" (:id context-1) [context-2-id])) 
      (:id (create-issue "issue-2" (:id context-1) [context-3-id]))
      (is (= (list [context-4-id ["context-4" 0 true]]
                   [context-3-id ["context-3" 1 true]]
                   [(:id context-1) ["context-1" 2 false]]
                   [context-2-id ["context-2" 1 false]]) 
             (second (:issues (repository/list-resources {:cmd :fetch-context
                                                          :arg [context-1 false]} db))))))))
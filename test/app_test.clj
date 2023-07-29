(ns app-test
  (:require [clojure.test :refer [deftest testing is] :as t]
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
  (repository/list-resources 
   {:cmd              :insert-issue
    :arg              {:title title}
    :selected-context {:id   context-id
                       :data {:selected-secondary-contexts 
                              selected-secondary-contexts-ids}}} db))

(deftest repository 
  (testing "base case"
    (reset-db)
    (let [context (create-context "abc")]
      (is (= 
           "abc"
           (:title (:selected-context (repository/fetch-context
                                       db
                                       {}
                                       [context false])))))))
  (testing "update a context"
    (reset-db)
    (let [context (select-keys (create-context "abc")
                               [:title :id])
          _ (update-context (assoc context
                                   :title "abc1"
                                   :data {:a ["1" "2"]}))
          context (first (:contexts (repository/search-contexts db "")))]
      (is (=
           {:a ["1" "2"]}
           (:data (:selected-context (repository/fetch-context db {} [context false]))))))))

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
      (create-issue "issue-1" (:id context-1) [context-2-id]) 
      (create-issue "issue-2" (:id context-1) [context-3-id])
      (is (= (list [context-4-id ["context-4" 0 true]]
                   [context-3-id ["context-3" 1 true]]
                   [(:id context-1) ["context-1" 2 false]]
                   [context-2-id ["context-2" 1 false]]) 
             (second (:issues (repository/fetch-context db
                                                        {}
                                                        [context-1 false]))))))))

(deftest link-issue-to-issue
  (testing "with local search"
    (reset-db)
    (let [context-1 (create-context "context-1")
          _ (create-issue "issue-1" (:id context-1) [])
          _ (create-issue "issue-2" (:id context-1) []) 
          opts {:active-search :issues
                :q             "issue-2"}
          opts (repository/list-resources opts db)
          issue-2 (first (first (:issues opts)))
          opts {:active-search :issues
                :q             "issue-1"} 
          opts (repository/list-resources opts db)
          issue-1 (first (first (:issues opts)))
          opts (repository/fetch-context db opts [context-1 true]) 
          opts (repository/fetch-issue db opts [issue-1 false])
          opts (merge opts (repository/start-linking-selected-issue-to-issue-with-local-search 
                            db
                            (repository/make-search-issues opts)))
          opts (repository/finish-linking-selected-issue db opts (:id issue-2))]
      (is (= "issue-2" (:title (first (:related_issues (:selected-issue opts)))))))))

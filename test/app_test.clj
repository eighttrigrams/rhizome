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
  (jdbc/execute-one! db ["delete from collections"])
  (jdbc/execute-one! db ["delete from issue_issue"])
  (jdbc/execute-one! db ["delete from issues"]))

(defn- create-context [title]
  (:selected-context 
   ((repository/list-resources {:db db}) 
    {:cmd :insert-context
     :arg {:title title}})))

(defn- update-context [context]
  ((repository/update-context {:db db})
   {}
   {:context {:context context}}))

(defn- create-issue [title 
                     context-id 
                     selected-secondary-contexts-ids]
  ((repository/insert-issue {:db db}) 
   {:selected-context {:id   context-id
                       :data 
                       {:views 
                        {:current
                         {:selected-secondary-contexts selected-secondary-contexts-ids}}}}} 
   {:title title}
   nil)
  (-> {:active-search :issues
       :q             title}
      ((repository/list-resources {:db db}))
      :issues
      ffirst))

(deftest repository 
  (testing "base case"
    (reset-db)
    (let [context (create-context "abc")]
      (is (= 
           "abc"
           (:title (:selected-context ((repository/fetch-context
                                        {:db db})
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
           ["1" "2"]
           (:a (:data (:selected-context ((repository/fetch-context {:db db}) {} [context false])))))))))

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
             (second (:issues ((repository/fetch-context {:db db})
                                                        {}
                                                        [context-1 false]))))))))

(deftest link-issue-to-issue
  (testing "with local search"
    (reset-db)
    (let [context-1 (create-context "context-1")
          issue-1   (create-issue "issue-1" (:id context-1) [])
          issue-2   (create-issue "issue-2" (:id context-1) [])
          _issue-3   (create-issue "issue-3" (:id context-1) [])
          opts      ((repository/fetch-context {:db db}) {} [context-1 true]) 
          opts      (merge opts ((repository/select-issue {:db db}) opts issue-1 false))
          opts      (merge opts (repository/start-linking-selected-issue-to-issue-with-local-search 
                                 db
                                 (repository/make-search-issues opts)))
          opts      (merge opts ((repository/finish-linking-issue {:db db}) opts (:id issue-2)))
          _         (is (= "issue-2" (:title (first (:related_issues (:selected-issue opts))))))
          
          opts      (merge opts (repository/start-linking-selected-issue-to-issue-with-local-search
                                 db
                                 (repository/make-search-issues opts)))
          issues (-> (merge opts {:q ""})
                     ((repository/list-resources {:db db}))
                     :issues
                     first)]
      (is (= 1 (count issues)))
      (is (= "issue-3" (:title (first issues)))))))

(deftest link-selected-issue-to-context 
  (testing "base case"
    (reset-db)
    (let [context-1 (create-context "context-1")
          _context-2 (create-context "context-2")
          issue-1   (create-issue "issue-1" (:id context-1) []) 
          
          opts      ((repository/select-issue {:db db}) {} issue-1 false)
          opts      (merge opts (repository/start-linking-selected-issue-to-context-with-local-search db opts))
          _ (is (= 1 (count (:contexts opts))))
          contexts  (-> (merge opts {:q ""})
                        ((repository/list-resources {:db db}))
                        :contexts)]
      (is (= 1 (count contexts))))))

(deftest link-issue-to-selected-context
  (testing "display the correct issues"
    (reset-db)
    (let [context-1 (create-context "context-1")
          context-2 (create-context "context-2")
          _issue-1   (create-issue "issue-1" (:id context-1) [])
          opts      ((repository/fetch-context {:db db}) {} [context-2 true])
          opts      (merge opts (repository/start-linking-issue-to-selected-context
                                 db
                                 (repository/make-search-issues opts)))
          _ (is (= 2 (count (first (:issues opts)))))
          opts      ((repository/fetch-context {:db db}) {} [context-1 true])
          opts      (merge opts (repository/start-linking-issue-to-selected-context
                                 db
                                 (repository/make-search-issues opts)))
          _ (is (= 1 (count (first (:issues opts)))))]))
  (testing "link issue to selected context"
    (reset-db)
    (let [context-1 (create-context "context-1")
          context-2 (create-context "context-2")
          issue-1  (create-issue "issue-1" (:id context-1) [])
          opts      ((repository/fetch-context {:db db}) {} [context-2 true])
          opts      (merge opts
                           ((repository/change-secondary-contexts-selection {:db db})
                            (assoc-in opts
                                      [:selected-context :context :context]
                                      context-1)))
          opts      (merge opts (repository/start-linking-issue-to-selected-context
                                 db
                                 (repository/make-search-issues opts)))
          opts      (merge opts ((repository/finish-linking-issue {:db db}) 
                                 opts (:id issue-1)))
          _         (is (= '("context-1" "context-2")
                           (vals (:contexts (:data (ffirst (:issues opts)))))))])))

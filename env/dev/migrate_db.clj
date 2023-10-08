(ns migrate-db
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [clojure.test :as t]
            [datastore.contexts :as contexts]
            datastore
            [datastore.issues :as issues]
            [datastore.search :as search]))

(defn- migrate-single-context [db {:keys [id title] :as context} context-id]
  (let [issue (issues/new-issue db {:title title} context-id #{} false)
        new-issue {:issue (merge 
                           (select-keys context [:tags :short_title :data])
                           (select-keys issue [:id :title]))}
        contained-issues-ids (map :id (first (search/search-issues db {:selected-context context})))]
    (issues/update-issue
     db
     new-issue)
    (doall (for [contained-issue-id contained-issues-ids]
             ;; TODO insert contained-in relation from issue to issue
             (prn "." contained-issue-id)))
    ;; TODO !! there are issues that, when linked only to that context
    ;; will get deleted; make sure that doesn't happen
    (datastore/delete-context db {:id id})))

(defn- seed-data [db]
  (let [{:keys [id] :as context} (contexts/new-context db {:title "test-context-1"})
        _ (contexts/update-context db {:context (assoc context :data {:hallo "1"}
                                                               :tags "a b c"
                                                               :short_title "101")})
        _ (issues/new-issue db {:title "test-issue-1"} id #{} false)]))

(defn- test-results [db]
  (t/is (= "test-context-1" (:title (first (first (search/search-issues db {}))))))
  ;; TODO we still have a problem here; issues short title are more restrictive because of short_title ints
  (t/is (= "101" (:short_title (first (first (search/search-issues db {}))))))
  (t/is (= "a b c" (:tags (first (first (search/search-issues db {}))))))
  #_(t/is (= {:hallo 1} (:data (ffirst (search/search-issues db {})))))
  )

(defn- clean-db [db]
  (let [ _ (prn "." (jdbc/execute! db ["delete from events"]))
        _ (prn "." (jdbc/execute! db ["delete from issue_issue"]))
        _ (prn "." (jdbc/execute! db ["delete from context_issue"]))
        _ (prn "." (jdbc/execute! db ["delete from issues"]))
        _ (prn "." (jdbc/execute! db ["delete from contexts"]))]))

(comment
  (let [db  (:db (read-string (slurp "./config.edn")))
        _ (clean-db db)
        _ (seed-data db)
        {:keys [id]} (contexts/new-context db {:title "migrated"})]
    (doall (for [context (filter #(not= "migrated" (:title %)) (search/search-contexts db {}))]
             (migrate-single-context db context id)))
    (test-results db)))
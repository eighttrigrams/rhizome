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
        {:keys [id]} (issues/new-issue db {:title "test-issue-1"} id #{} false)]
    [id]))

(defn- test-results [db ids]
  (let [issues (remove #(contains? ids (:id %)) 
                        (first (search/search-issues db {})))
        issue (first issues)]
    (t/is (= "test-context-1" (:title issue)))
  ;; TODO we still have a problem here; issues short title are more restrictive because of short_title ints
    (t/is (= "101" (:short_title issue)))
    (t/is (= "a b c" (:tags issue)))
    #_ (t/is (= {:hallo 1} (:data (ffirst (search/search-issues db {}))))))
  )

(defn- clean-db [db]
  (jdbc/execute! db ["delete from events; delete from issue_issue; delete from context_issue; delete from issues; delete from contexts;"]))

(comment
  (let [db  (:db (read-string (slurp "./config.edn")))
        _ (clean-db db)
        ids (seed-data db)
        {:keys [id]} (contexts/new-context db {:title "migrated"})]
    (doall (for [context (filter #(not= "migrated" (:title %)) (search/search-contexts db {}))]
             (migrate-single-context db context id)))
    (test-results db ids)))
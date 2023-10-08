(ns migrate-db
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.contexts :as contexts]
            datastore
            [datastore.issues :as issues]
            [datastore.search :as search]))

(defn- migrate-single-context [db {:keys [id title] :as context} context-id]
  (let [issue (issues/new-issue db {:title title} context-id #{} false)
        new-issue {:issue (merge 
                           (select-keys context [:tags :short_title])
                           (select-keys issue [:id :title]))}
        contained-issues-ids (map :id (first (search/search-issues db {:selected-context context})))]
    (issues/update-issue
     db
     new-issue)
    (doall (for [contained-issue-id contained-issues-ids]
             ;; TODO insert contained-in relation from issue to issue
             (prn "." contained-issue-id)))
    (datastore/delete-context db {:id id})))

(defn- seed-data [db]
  (let [{:keys [id]} (contexts/new-context db {:title "test-context-1"})
        _ (issues/new-issue db {:title "test-issue-1"} id #{} false)]))

(comment
  (let [db  (:db (read-string (slurp "./config.edn")))
        _ (seed-data db)
       context (first (filter #(not= "migrated" (:title %)) (search/search-contexts db {})))
        {:keys [id]} (contexts/new-context db {:title "migrated"})]
    (prn (migrate-single-context db context id))
    
    #_(jdbc/execute! db (sql/format {:select :contexts.*
                                     :from [:contexts]
                                     
                                     :limit 1}))
    ))
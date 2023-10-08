(ns migrate-db
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.contexts :as contexts]
            [datastore.issues :as issues]
            [datastore.search :as search]))

(defn- migrate-single-context [db {:keys [title] :as context} context-id]
  (let [issue (issues/new-issue db {:title title} context-id #{} false)
        new-issue {:issue (merge 
                           (select-keys context [:tags :short_title])
                           (select-keys issue [:id :title]))}]
    (issues/update-issue
     db
     new-issue)))

(comment
  (let [db  (:db (read-string (slurp "./config.edn")))
       context (first (filter #(not= "migrated" (:title %)) (search/search-contexts db {})))
        {:keys [id]} (contexts/new-context db {:title "migrated"})]
    (prn (migrate-single-context db context id))
    
    #_(jdbc/execute! db (sql/format {:select :contexts.*
                                     :from [:contexts]
                                     
                                     :limit 1}))
    ))
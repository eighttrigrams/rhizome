(ns migrate-db
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.contexts :as contexts]
            [datastore.search :as search]))

(comment
  (let [db  (:db (read-string (slurp "./config.edn")))]
    
    
    #_(jdbc/execute! db (sql/format {:select :contexts.*
                                     :from [:contexts]
                                     :limit 1}))
    (first (search/search-contexts db {}))))
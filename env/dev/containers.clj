(ns containers
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.get-item :refer [get-item]]
            datastore))

(comment
  (let [db
        #_{:dbtype   "postgresql"
           :dbname   "cometoid_dev"
           :user     "daniel"
           :password "abcdef"
           :port     5437
           :hostname "127.0.0.1"}
        {:dbtype   "postgresql"
         :dbname   "cometoid"
         :user     "daniel"
         :password "abcdef"
         :port     5437
         :hostname "127.0.0.1"}
        ids (jdbc/execute! db ["select id from issues 
                                order by updated_at desc
                                limit 15000"])]
    (doall (for [id ids]
             (try
               (datastore/derive-containers-of-item! db {:id (:issues/id id)})
               (catch Exception e
                 (prn "alarm" (.getMessage e))))))))
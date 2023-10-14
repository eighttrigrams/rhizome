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
                                limit 15000"])]
    (doall (for [id ids]
             (try
               (let [item (get-item db {:id (:issues/id id)})]
                 (when (= 0 (count (:contexts (:data item))))
                   (datastore/derive-containers-of-item! db {:id (:issues/id id)})))
               (catch Exception e
                 (prn "alarm" (.getMessage e))))))))
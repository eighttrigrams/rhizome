(ns orphaned
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.items :refer [get-item]]))

(comment
  (let [db
        {:dbtype   "postgresql"
           :dbname   "cometoid_dev"
           :user     "daniel"
           :password "abcdef"
           :port     5437
           :hostname "127.0.0.1"}
        #_{:dbtype   "postgresql"
         :dbname   "cometoid"
         :user     "daniel"
         :password "abcdef"
         :port     5437
         :hostname "127.0.0.1"}
        ids (jdbc/execute! db ["select id from items 
                                where is_context = 'f' 
                                
                                limit 15000"])]
    (doall (for [id ids]
             (try
               (let [item (get-item db {:id (:items/id id)})]
                 (when (= 0 (count (:contexts item)))
                   (tap> (:id item))
                   (jdbc/execute-one! db ["insert into relations
                                           (owner_id,target_id)
                                           values (?,?)"
                                          11701 (:id item)])))
               (catch Exception e
                 (prn "alarm2" (.getMessage e))))))))
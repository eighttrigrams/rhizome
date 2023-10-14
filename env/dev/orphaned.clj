(ns orphaned
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.get-item :refer [get-item]]))

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
        ids (jdbc/execute! db ["select id from issues 
                                where is_context = 'f' 
                                
                                limit 15000"])]
    (doall (for [id ids]
             (try
               (let [item (get-item db {:id (:issues/id id)})]
                 (when (= 0 (count (:contexts item)))
                   (tap> (:id item))
                   (jdbc/execute-one! db ["insert into collections
                                           (container_id,item_id)
                                           values (?,?)"
                                          11701 (:id item)])))
               (catch Exception e
                 (prn "alarm2" (.getMessage e))))))))
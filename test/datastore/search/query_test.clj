(ns datastore.search.query-test
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

(defn- create-item [title is-context?]
  (:issues/id (jdbc/execute-one! db ["insert into issues(title,inserted_at,updated_at,updated_at_ctx,is_context) values (?,NOW(),NOW(),NOW(),?)", title, is-context?] {:return-keys true})))

(deftest abc
  (testing "base-case"
    (reset-db)
    (let [cont1-id (create-item "cont1" true)
          cont2-id (create-item "cont2" true)
          cont3-id (create-item "cont3" true)
          _cont4-id (create-item "cont4" true)
          it1-id (create-item "it1" false)
          it2-id (create-item "it2" false)
          it3-id (create-item "it3" false)]
      (jdbc/execute-one! db ["insert into collections(container_id,item_id) values (?,?),(?,?),(?,?),(?,?),(?,?),(?,?)" cont1-id it1-id cont1-id it2-id cont2-id it1-id cont2-id it2-id cont3-id it1-id cont3-id it2-id])
      ;; (jdbc/execute-one! db ["insert into collections(container_id,item_id) values (?,?),(?,?),(?,?),(?,?),(?,?)" cont1-id it1-id cont1-id it2-id cont2-id it1-id cont2-id it2-id cont3-id it1-id])
      (jdbc/execute-one! db ["insert into issue_issue(left_id,right_id) values (?,?),(?,?)" cont1-id it2-id cont1-id it3-id])


      )))
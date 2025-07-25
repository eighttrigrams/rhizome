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
  (jdbc/execute-one! db ["delete from relations"])
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
      (jdbc/execute-one! db ["insert into relations(owner_id,target_id) values (?,?),(?,?),(?,?),(?,?),(?,?),(?,?)" cont1-id it1-id cont1-id it2-id cont2-id it1-id cont2-id it2-id cont3-id it1-id cont3-id it2-id])
      ;; (jdbc/execute-one! db ["insert into relations(owner_id,target_id) values (?,?),(?,?),(?,?),(?,?),(?,?)" cont1-id it1-id cont1-id it2-id cont2-id it1-id cont2-id it2-id cont3-id it1-id])
      ;; select ppg.id,ppg.title,array_agg(ppg.cid) from (select pp.id, pp.cid, pp.title, array_agg(pp.rel) from (SELECT issues.title, issues.id, 1 as rel, issue_issue.left_id cid FROM issues JOIN issue_issue ON issues.id = issue_issue.right_id WHERE issue_issue.left_id IN (16964,16965) UNION ALL SELECT issues.title, issues.id, 2 as rel, relations.owner_id as cid FROM issues JOIN relations ON issues.id = relations.target_id WHERE relations.owner_id IN (16964,16965)) as pp group by pp.id, pp.cid, pp.title) as ppg group by ppg.id,ppg.title having count(ppg.array_agg) = 2;
      (jdbc/execute-one! db ["insert into issue_issue(left_id,right_id) values (?,?),(?,?)" cont1-id it2-id cont1-id it3-id])


      )))
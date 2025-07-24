(require '[babashka.pods :as pods])
(pods/load-pod 'org.babashka/postgresql "0.1.0")
(require '[pod.babashka.postgresql :as pg]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(defn db [env]
  (if (= :prod env)
    {:dbtype   "postgresql"
     :dbname   "cometoid"
     :user     "daniel"
     :password "abcdef"
     :port     5437
     :hostname "127.0.0.1"}
    {:dbtype   "postgresql"
     :dbname   "cometoid_dev"
     :user     "daniel"
     :password "abcdef"
     :port     5437
     :hostname "127.0.0.1"}))

(defn update-substack-article [id url]
  (as-> id % 
    (pg/execute-one! (db :prod) ["select issues.id, issues.title, issues.data from issues where id = ?" %]) 
    (assoc-in % [:issues/data :resource-links :substack-article] url) 
    (get % :issues/data)
    (json/generate-string %)
    (str/replace % "'" "''")
    (pg/execute-one! (db :prod) [(str "update issues set data = '" % "' where id = ?") id])))

(defn insert-relation [id]
  (pg/execute! (db :prod) ["insert into relations (container_id, item_id) select 30065, ? where not exists (select * from relations where container_id = 30065 and item_id = ?)" id id])
  (pg/execute! (db :prod) ["insert into relations (container_id, item_id) select 14721, ? where not exists (select * from relations where container_id = 14721 and item_id = ?)" id id])
  (pg/execute! (db :prod) ["insert into relations (container_id, item_id) select 10913, ? where not exists (select * from relations where container_id = 10919 and item_id = ?)" id id]))

(defn update-badge [id]
  (as-> id % 
    (pg/execute-one! (db :prod) ["select issues.id, issues.title, issues.data from issues where id = ?" %])
    ;; (assoc-in % [:issues/data :contexts (keyword (str 30065))] {:show-badge? true :title "vaporgrid.substack"})  ;; <----------- not necessary anymore probably
    (assoc-in % [:issues/data :contexts (keyword (str 14721))] {:show-badge? true :title "Substack"}) 
    (assoc-in % [:issues/data :contexts (keyword (str 10913))] {:show-badge? true :title "Articles"}) 
    ;; (assoc-in % [:issues/data :contexts (keyword (str 22971))] {:show-badge? true :title "VaporGrid"}) 
    ;; (assoc-in % [:issues/data :contexts] {}) 
    (get % :issues/data)
    (pg/execute-one! (db :prod) [(str "update issues set data = '" (json/generate-string %) "' where id = ?") id])))

(comment
  (let [id 14408]
    (update-substack-article id  "https://markferreira.substack.com/p/the-masculinity-of-cooking")
    #_(insert-relation id)
    #_(update-badge id))
  )

;; "{"contexts":{"10934":{"title":"Pod Ep.","show-badge?":true},"22971":{"title":"VaporGrid","show-badge?":true}},
;; "highlighted-secondary-contexts":[],"resource-links":{"substack-article":"https://vaporgrid.substack.com/p/2-stoner"}}"
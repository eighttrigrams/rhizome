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
    (pg/execute-one! (db :prod) ["select items.id, items.title, items.data from items where id = ?" %]) 
    (assoc-in % [:items/data :resource-links :substack-article] url) 
    (get % :items/data)
    (json/generate-string %)
    (str/replace % "'" "''")
    (pg/execute-one! (db :prod) [(str "update items set data = '" % "' where id = ?") id])))

(defn insert-relation [id]
  (pg/execute! (db :prod) ["insert into relations (owner_id, target_id) select 30065, ? where not exists (select * from relations where owner_id = 30065 and target_id = ?)" id id])
  (pg/execute! (db :prod) ["insert into relations (owner_id, target_id) select 14721, ? where not exists (select * from relations where owner_id = 14721 and target_id = ?)" id id])
  (pg/execute! (db :prod) ["insert into relations (owner_id, target_id) select 10913, ? where not exists (select * from relations where owner_id = 10919 and target_id = ?)" id id]))

(defn update-badge [id]
  (as-> id % 
    (pg/execute-one! (db :prod) ["select items.id, items.title, items.data from items where id = ?" %])
    ;; (assoc-in % [:issues/data :contexts (keyword (str 30065))] {:show-badge? true :title "vaporgrid.substack"})  ;; <----------- not necessary anymore probably
    (assoc-in % [:issues/data :contexts (keyword (str 14721))] {:show-badge? true :title "Substack"}) 
    (assoc-in % [:issues/data :contexts (keyword (str 10913))] {:show-badge? true :title "Articles"}) 
    ;; (assoc-in % [:issues/data :contexts (keyword (str 22971))] {:show-badge? true :title "VaporGrid"}) 
    ;; (assoc-in % [:issues/data :contexts] {}) 
    (get % :issues/data)
    (pg/execute-one! (db :prod) [(str "update items set data = '" (json/generate-string %) "' where id = ?") id])))

(comment
  (let [id 37913]
    (update-substack-article
      id
      "https://eighttrigrams.substack.com/p/here-to-stay")
    #_(insert-relation id)
    #_(update-badge id)))

;; "{"contexts":{"10934":{"title":"Pod Ep.","show-badge?":true},"22971":{"title":"VaporGrid","show-badge?":true}},
;; "highlighted-secondary-contexts":[],"resource-links":{"substack-article":"https://vaporgrid.substack.com/p/2-stoner"}}"
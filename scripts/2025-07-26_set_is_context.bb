(require '[babashka.pods :as pods])
(pods/load-pod 'org.babashka/postgresql "0.1.0")
(require '[pod.babashka.postgresql :as pg]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def db #_{:dbtype   "postgresql"
           :host     "your-db-host-name"
           :dbname   "your-db"
           :user     "develop"
           :password "develop"
           :port     5432}
  {:dbtype   "postgresql"
   :dbname   "cometoid_dev"
   :user     "daniel"
   :password "abcdef"
   :port     5437
   :hostname "127.0.0.1"})

(defn- make-contexts [z contexts]
  (into {} (map (fn [[idx context]]
                  (let [is-context? (get z (Integer/parseInt (name idx)))]
                    [idx (if (string? context) ;; <---- we won't need this anymore after one complete run
                           {:show-badge? true ;;;;; <------ we might want to fix this at some point
                            :title context
                            :is-context? is-context?}
                           (assoc context :is-context? is-context?))]))
                contexts)))

(->> (pg/execute! db 
                  ["select items.id, items.data, array_agg(related_items.id) related_items_id, array_agg(related_items.is_context) related_items_is_context\n
                  from items join \n
                  relations on items.id = relations.target_id join items related_items on related_items.id = relations.owner_id \n
                    where items.id > 29403 \n
                group by items.id"]) 
     (map (fn [{:keys [items/id items/data related_items_id related_items_is_context] :as _item}]
            (let [z (zipmap related_items_id related_items_is_context)
                  stringy (-> (json/generate-string (update data :contexts (partial make-contexts z)))
                              (str/replace "'" "''"))]
              (prn "---" id)
              (try (pg/execute! db 
                                [(str "update items set data = '" stringy "' where id = " id)])
                   (catch Exception e
                     (prn "got an exception with " id "..." (.getMessage e) "..." stringy))))))
     count)

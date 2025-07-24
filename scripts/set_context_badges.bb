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

(defn- make-contexts [{:keys [contexts]}
                      {:keys [context_ids]}]
  (reduce (fn [acc context_id]
            (let [context-id (keyword (str context_id))]
              (if (get contexts context-id)
                (assoc-in contexts [context-id :show-badge?] false)
                acc)))
          contexts context_ids))

(->> (pg/execute! db ["select items.id,items.data,array_agg(contexts.id) context_ids \n
                  from issues items join \n
                  relations on items.id = relations.item_id join issues contexts on contexts.id = relations.container_id \n
                  where relations.show_badge = false \n
                group by items.id"]) 
     (map (fn [{:keys [issues/id issues/data] :as item}]
            (let [stringy (-> (json/generate-string (assoc data :contexts (make-contexts data item)))
                              (str/replace "'" "''"))]
              (try (pg/execute! db 
                                [(str "update issues set data = '" stringy "' where id = " id)])
                   (catch Exception e
                     (prn "got an exception with " id "..." (.getMessage e) "..." stringy))))))
     count)

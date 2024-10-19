(require '[babashka.pods :as pods])

;; load from pod registry:
(pods/load-pod 'org.babashka/postgresql "0.1.0")
;; or load from system path:
;; (pods/load-pod "pod-babashka-postgresql")
;; or load from a relative or absolute path:
;; (pods/load-pod "./pod-babashka-postgresql")

(require '[pod.babashka.postgresql :as pg]
         '[cheshire.core :as json])

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

(defn- make-contexts [{:keys [context_ids context_titles context_short_titles]}]
  (into {} (map (fn [context-id context-title context-short-title] [context-id {:title (or context-short-title context-title)
                                                                                :show-badge? true}])
                context_ids context_titles context_short_titles)))

(->> (pg/execute! db ["select items.id,items.data,array_agg(contexts.id) context_ids,array_agg(contexts.title) context_titles,array_agg(contexts.short_title) context_short_titles \n
                  from issues items join \n
                  collections on items.id = collections.item_id join issues contexts on contexts.id = collections.container_id \n
                group by items.id"])
     (map (fn [{:keys [issues/id issues/data] :as item}]
            (pg/execute! db 
                         [(str "update issues set data = '" (json/generate-string (assoc data :contexts (make-contexts item))) "' where id = " id)]))))
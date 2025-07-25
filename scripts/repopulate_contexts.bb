(require '[babashka.pods :as pods])

;; load from pod registry:
(pods/load-pod 'org.babashka/postgresql "0.1.0")
;; or load from system path:
;; (pods/load-pod "pod-babashka-postgresql")
;; or load from a relative or absolute path:
;; (pods/load-pod "./pod-babashka-postgresql")

(require '[pod.babashka.postgresql :as pg]
         '[cheshire.core :as json]
         '[clojure.string :as str])


;; insert into relations(owner_id,target_id,show_badge) select left_id,right_id,false from issue_issue where not exists (select 1 from relations where owner_id = left_id and target_id = right_id);


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
  (into {} (map (fn [context-id context-title context-short-title]
                  [context-id {:title       (or (and context-short-title
                                                     (not-empty context-short-title))
                                                context-title)
                               :show-badge? true}])
                context_ids context_titles context_short_titles)))

(->> (pg/execute! db ["select items.id,items.data,array_agg(contexts.id) context_ids,array_agg(contexts.title) context_titles,array_agg(contexts.short_title) context_short_titles \n
                  from issues items join \n
                  relations on items.id = relations.target_id join issues contexts on contexts.id = relations.owner_id \n
                group by items.id"])
     (map (fn [{:keys [issues/id issues/data] :as item}]
            (let [stringy (-> (json/generate-string (assoc data 
                                                           :contexts (make-contexts item)
                                                           :highlighted-secondary-contexts []))
                              (str/replace "'" "''"))]
              (try (pg/execute! db 
                                [(str "update issues set data = '" stringy "' where id = " id)])
                   (catch Exception e
                     (prn "got an exception with " id "..." (.getMessage e) "..." stringy)))))))
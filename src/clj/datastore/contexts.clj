(ns datastore.contexts
  (:require [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.helpers
             :refer [un-namespace-keys]]
            [datastore.items :refer [get-item update-item] :rename {get-item get-context}]))

(defn new-context [db {title :title}]
  (-> (jdbc/execute-one!
       db
       (sql/format {:insert-into [:issues]
                    :columns     [:inserted_at
                                  :updated_at
                                  :updated_at_ctx
                                  :title
                                  :is_context]
                    :values      [[[:raw "NOW()"]
                                   [:raw "NOW()"]
                                   [:raw "NOW()"]
                                   [:inline title]
                                   true]]})
       {:return-keys true})
      un-namespace-keys
      (dissoc :searchable)))

(defn update-context [db {:keys [context]}]
  (update-item db context :context)
  (get-context db context))

(defn update-context-description [db {:keys [id description]}]
  (jdbc/execute-one! db
                     (sql/format {:update [:issues]
                                  :set    {:description    [:inline description]
                                           :updated_at_ctx [:raw "NOW()"]}
                                  :where  [:= :id [:inline id]]})
                     {:return-keys true})
  (get-context db {:id id}))

(defn store-current-view [db {:keys [id] :as selected-context} {:keys [title]}]
  (let [data (:data (get-context db selected-context))
        data (update-in data [:views :stored] conj {:title title
                                                    :view (:current (:views data))})]
    (jdbc/execute-one! db (sql/format {:update [:issues]
                                       :set    {:data [:inline (json/generate-string data)]}
                                       :where  [:= :id [:inline id]]}))) 
  (get-context db selected-context))

(defn load-stored-context [db {:keys [id] :as selected-context} idx]
  (let [data (:data (get-context db selected-context))
        data (assoc-in data [:views :current] 
                       (-> data :views :stored (get idx) :view))]
    (jdbc/execute-one! db (sql/format {:update [:issues]
                                       :set    {:data [:inline (json/generate-string data)]}
                                       :where  [:= :id [:inline id]]})))
  (get-context db selected-context))

;; https://stackoverflow.com/a/18319708
(defn vec-remove
  "remove elem in coll"
  [pos coll]
  (into (subvec coll 0 pos) (subvec coll (inc pos))))

(defn remove-stored-context [db {:keys [id] :as selected-context} idx]
  (let [data (:data (get-context db selected-context))
        data (update-in data [:views :stored] #(vec-remove idx %))]
    (jdbc/execute-one! db (sql/format {:update [:issues]
                                       :set    {:data [:inline (json/generate-string data)]}
                                       :where  [:= :id [:inline id]]})))
  (get-context db selected-context))

(defn show-events [db {:keys [id] :as context}]
  (let [data (-> (get-context db context)
                 :data
                 (assoc-in [:views :current :events-view] 1))]
    (jdbc/execute-one! db (sql/format {:update [:issues]
                                       :set    {:data [:inline (json/generate-string data)]}
                                       :where  [:= :id [:inline id]]}))
    (get-context db context)))

(defn show-past-events [db {:keys [id] :as context}]
  (let [data (-> (get-context db context)
                 :data
                 (assoc-in [:views :current :events-view] 2))]
    (jdbc/execute-one! db (sql/format {:update [:issues]
                                       :set    {:data [:inline (json/generate-string data)]}
                                       :where  [:= :id [:inline id]]}))
    (get-context db context)))

(defn deselect-events [db {:keys [id] :as context}]
  (let [data (-> (get-context db context)
                 :data
                 (assoc-in [:views :current :events-view] 0))]
    (jdbc/execute-one! db (sql/format {:update [:issues]
                                       :set    {:data [:inline (json/generate-string data)]}
                                       :where  [:= :id [:inline id]]}))
    (get-context db context)))

(defn cycle-search-mode [db {:keys [id] :as context}]
  (let [data (-> (get-context db context)
                 :data
                 (update-in [:views :current :search-mode]
                            #(mod (inc (or % 0)) 4)))]
    (jdbc/execute-one! db (sql/format {:update [:issues]
                                       :set    {:data [:inline (json/generate-string data)]}
                                       :where  [:= :id [:inline id]]}))
    (get-context db context)))

(defn reprioritize-context [db {:keys [id]}]
  (jdbc/execute! db (sql/format {:update [:issues]
                                 :set {:updated_at_ctx [:raw "NOW()"]}
                                 :where [:= :id [:inline id]]})))

(ns datastore.contexts
  (:require [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.helpers
             :refer [un-namespace-keys]]
            [datastore.get-item :refer [get-item] :rename {get-item get-context}]))

(defn new-context [db {title :title}]
  (-> (jdbc/execute-one!
       db
       (sql/format {:insert-into [:issues]
                    :columns     [:inserted_at
                                  :updated_at
                                  :title
                                  :is_context]
                    :values      [[[:raw "NOW()"]
                                   [:raw "NOW()"]
                                   [:inline title]
                                   true]]})
       {:return-keys true})
      un-namespace-keys
      (dissoc :searchable)))

(defn- update-context' [db {:keys [id title short_title tags data] :as item}]
  (let [old-data (:data (get-context db item))]
    (jdbc/execute-one! db
                       (sql/format {:update [:issues]
                                    :where  [:= :id [:inline id]]
                                    :set    {:title       [:inline title]
                                             :short_title [:inline short_title]
                                             :tags        [:inline tags]
                                             :updated_at  [:raw "NOW()"]
                                             :data        [:inline (json/generate-string
                                                                    (merge old-data
                                                                           data))]}})
                       {:return-keys true})))

(defn update-context [db {:keys [context]}]
  (update-context' db context)
  (get-context db context))

(defn update-context-description [db {:keys [id description]}]
  (jdbc/execute-one! db
                     (sql/format {:update [:issues]
                                  :set    {:description [:inline description]
                                           :updated_at  [:raw "NOW()"]}
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

(defn cycle-context-preview [db {:keys [id] :as context}]
  (let [data (-> (get-context db context)
                 :data
                 (update-in [:views :current :context-preview]
                            not))]
    (jdbc/execute-one! db (sql/format {:update [:issues]
                                       :set    {:data [:inline (json/generate-string data)]}
                                       :where  [:= :id [:inline id]]}))
    (get-context db context)))

(defn cycle-search-mode [db {:keys [id] :as context}]
  (let [data (-> (get-context db context)
                 :data
                 (update-in [:views :current :search-mode]
                            #(mod (inc (or % 0)) 3)))]
    (jdbc/execute-one! db (sql/format {:update [:issues]
                                       :set    {:data [:inline (json/generate-string data)]}
                                       :where  [:= :id [:inline id]]}))
    (get-context db context)))

(defn cycle-notes-mode [db {:keys [id] :as context}]
  (let [data (-> (get-context db context)
                 :data
                 (update-in [:views :current :notes-mode]
                            #(if (nil? %) true (not %))))]
    (jdbc/execute-one! db (sql/format {:update [:issues]
                                       :set    {:data [:inline (json/generate-string data)]}
                                       :where  [:= :id [:inline id]]})))
  (get-context db context))

(defn reprioritize-context [db {:keys [id]}]
  (jdbc/execute! db (sql/format {:update [:issues]
                                 :set {:updated_at [:raw "NOW()"]}
                                 :where [:= :id [:inline id]]})))

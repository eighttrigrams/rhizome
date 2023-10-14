(ns datastore
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [cheshire.core :as json]
            [datastore.issues :as issues]
            [datastore.contexts :as contexts]
            [datastore.get-item :as get-item]
            [datastore.helpers
             :refer [un-namespace-keys]]))

;; entity types
;; - issues
;; - context
;; - context-issue-relation
;; TODO
;; think about groups
;; normal contexts are folders
;; groups are superfolders
;; directed acyclycal graph

;; TODO move to search ns, explore varags here, make tut about varargs and destructuring?
;; TODO in minimals, show examples which use substitution/formatting

(def get-issue get-item/get-item)

(def update-issue-description issues/update-issue-description)

(def link-issue issues/link-issue)

(def reprioritize-issue issues/reprioritize-issue) 

(defn derive-containers-of-item!
  "Sets the :contexts property under :data to either the provided
   value or takes it by calculating the contexts via get-item.
   @returns the updated item."
  ([db item] (derive-containers-of-item! db item nil))
  ([db {:keys [id] :as item} contexts]
   (let [item (get-item/get-item db item)
         data (:data item)
         data (assoc data :contexts (or contexts
                                        (:contexts item)))]
     (jdbc/execute-one! db
                        (sql/format {:update [:issues]
                                     :where  [:= :id [:inline id]]
                                     :set    {:data        [:inline (json/generate-string
                                                                     data)]}})
                        {}))
   (get-issue db item)))

(defn update-issue [db {:keys [issue] :as args}] 
  (derive-containers-of-item! db issue)
  (issues/update-issue db args))

(defn new-issue [db & args]
  (let [issue (apply issues/new-issue db args)]
    (derive-containers-of-item! db issue)
    issue))

(defn set-containers-of-item!
  "Sets the containers of a given item and calculated the derived ones.
   @params container-ids
   @returns the updated item."
  [db selected-issue container-ids]
  (jdbc/execute! db (sql/format {:delete-from [:collections]
                                 :where [:= :item_id [:inline (:id selected-issue)]]}))
  (doall (for [container-id container-ids]
           (jdbc/execute! db (sql/format {:insert-into [:collections]
                                          :columns [:item_id :container_id]
                                          :values [[[:inline (:id selected-issue)]
                                                    [:inline container-id]]]}))))
  (derive-containers-of-item! db selected-issue
                              (->> container-ids
                                   (map #(do {:id %}))
                                   (map (partial get-issue db))
                                   (map (fn [item] [(:id item)
                                                    (:title item)]))
                                   (into {}))))

(defn upgrade-issue-to-context! [db {:keys [id] :as item}]
  (jdbc/execute-one! db
                     (sql/format {:update [:issues]
                                  :where  [:= :id [:inline id]]
                                  :set    {:is_context true
                                           :updated_at  [:raw "NOW()"]}})
                     {:return-keys true})
  (derive-containers-of-item! db item))

(def delete-issue issues/delete-issue)

(def cycle-search-mode contexts/cycle-search-mode)

(def show-events contexts/show-events)

(def show-past-events contexts/show-past-events)

(def deselect-events contexts/deselect-events)

(def cycle-notes-mode contexts/cycle-notes-mode)

(def cycle-context-preview contexts/cycle-context-preview)

(def update-context-description contexts/update-context-description)

(def update-context contexts/update-context)

(def reprioritize-context contexts/reprioritize-context)

(def get-context get-item/get-item)

(def new-context contexts/new-context)

(def store-current-view contexts/store-current-view)

(def load-stored-context contexts/load-stored-context)

(def remove-stored-context contexts/remove-stored-context)

(defn delete-context
  [db {:keys [id]}]
  (doall
   (for [issue-relation ;
         (map un-namespace-keys 
              (jdbc/execute! db
                             (sql/format {:select :*
                                          :from   [:collections]
                                          :where  [:= :container_id id]})
                             {:return-keys true}))]
     (let [context-relations (map un-namespace-keys 
                                  (jdbc/execute! db
                                                 (sql/format {:select :*
                                                              :from   [:collections]
                                                              :where  [:= :item_id (:item_id issue-relation)]})
                                                 {:return-keys true}))]
       (if (= 1 (count context-relations))
         (issues/delete-issue db {:id (:item_id issue-relation)})
         (jdbc/execute! db
                        (sql/format {:delete-from [:collections]
                                     :where       [:and 
                                                   [:= :container_id id]
                                                   [:= :item_id (:item_id issue-relation)]]}))))))
  (jdbc/execute! db
                 (sql/format {:delete-from [:issues]
                              :where [:= :id id]})))

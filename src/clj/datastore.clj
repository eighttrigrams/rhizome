(ns datastore
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.issues :as issues]
            [datastore.contexts :as contexts]
            [datastore.get-item :as get-item]))

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

(def update-issue issues/update-issue)

(def update-issue-simple issues/update-issue-simple)

(def new-issue issues/new-issue)

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
                                                    [:inline container-id]]]})))))

(defn upgrade-issue-to-context! [db {:keys [id] :as item}]
  (jdbc/execute-one! db
                     (sql/format {:update [:issues]
                                  :where  [:= :id [:inline id]]
                                  :set    {:is_context true
                                           :updated_at_ctx [:raw "NOW()"]
                                           :updated_at  [:raw "NOW()"]}})
                     {:return-keys true})
  (get-item/get-item db item))

(def cycle-search-mode contexts/cycle-search-mode)

(def show-events contexts/show-events)

(def show-past-events contexts/show-past-events)

(def deselect-events contexts/deselect-events)

(def cycle-notes-mode contexts/cycle-notes-mode)

(def update-context-description contexts/update-context-description)

(def update-context contexts/update-context)

(def reprioritize-context contexts/reprioritize-context)

(def get-context get-item/get-item)

(def new-context contexts/new-context)

(def store-current-view contexts/store-current-view)

(def load-stored-context contexts/load-stored-context)

(def remove-stored-context contexts/remove-stored-context)

(defn get-contained-items-count [db id]
  (count (jdbc/execute! db
                        (sql/format {:select :*
                                     :from   [:collections]
                                     :where  [:= :container_id id]})
                        {:return-keys true})))

(defn delete-item
  [db {:keys [id]}]
  (issues/delete-date db id)
  (jdbc/execute! db (sql/format {:delete-from [:collections]
                                 :where [:= :item_id [:inline id]]}))
  (jdbc/execute! db (sql/format {:delete-from [:issue_issue]
                                 :where [:or
                                         [:= :left_id [:inline id]]
                                         [:= :right_id [:inline id]]]}))
  (jdbc/execute! db (sql/format {:delete-from [:issues]
                                 :where [:= :id [:inline id]]})))

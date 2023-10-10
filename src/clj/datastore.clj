(ns datastore
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.issues :as issues]
            [datastore.contexts :as contexts]
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

(def update-issue issues/update-issue)

(def get-issue issues/get-issue)

(def update-issue-description issues/update-issue-description)

(def new-issue issues/new-issue)

(def link-issue issues/link-issue)

(def reprioritize-issue issues/reprioritize-issue) 

(def link-issue-contexts issues/link-issue-contexts)

(def delete-issue issues/delete-issue)

(def cycle-search-mode contexts/cycle-search-mode)

(def show-events contexts/show-events)

(def show-past-events contexts/show-past-events)

(def deselect-events contexts/deselect-events)

(def cycle-notes-mode contexts/cycle-notes-mode)

(def update-context-description contexts/update-context-description)

(def update-context contexts/update-context)

(def reprioritize-context contexts/reprioritize-context)

(def get-context contexts/get-context)

(def new-context contexts/new-context)

(def store-current-view contexts/store-current-view)

(def load-stored-context contexts/load-stored-context)

(def remove-stored-context contexts/remove-stored-context)

(defn delete-context
  ([db context] (delete-context db context {}))
  ([db {:keys [id]} {:keys [;; this option was introduced for the migration
                            dont-delete-issues] :as _opts}]
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
        (if (and (not dont-delete-issues)
                 (= 1 (count context-relations)))
          (issues/delete-issue db {:id (:item_id issue-relation)})
          (jdbc/execute! db
                         (sql/format {:delete-from [:collections]
                                      :where [:and 
                                              [:= :container_id id]
                                              [:= :item_id (:item_id issue-relation)]]}))))))
   (jdbc/execute! db
                  (sql/format {:delete-from [:issues]
                               :where [:= :id id]}))))

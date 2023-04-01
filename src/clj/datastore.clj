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

(def mark-issue-important issues/mark-issue-important) 

(def link-issue-contexts issues/link-issue-contexts)

(def delete-issue issues/delete-issue)

(def cycle-search-mode contexts/cycle-search-mode)

(def update-context-description contexts/update-context-description)

(def update-context contexts/update-context)

(def get-context contexts/get-context)

(def new-context contexts/new-context)

(defn delete-context [db {:keys [id]}]
  (doall
   (for [issue-relation ;
         (map un-namespace-keys 
              (jdbc/execute! db
                             (sql/format {:select :*
                                          :from   [:context_issue]
                                          :where  [:= :context_id id]})
                             {:return-keys true}))]
     (let [context-relations (map un-namespace-keys 
                                  (jdbc/execute! db
                                                 (sql/format {:select :*
                                                              :from   [:context_issue]
                                                              :where  [:= :issue_id (:issue_id issue-relation)]})
                                                 {:return-keys true}))]
       (if (= 1 (count context-relations))
         (issues/delete-issue db {:id (:issue_id issue-relation)})
         (jdbc/execute! db
                        (sql/format {:delete-from [:context_issue]
                                     :where [:and 
                                             [:= :context_id id]
                                             [:= :issue_id (:issue_id issue-relation)]]}))))))
  (jdbc/execute! db ;; TODO maybe use fn from contexts ns
                 (sql/format {:delete-from [:context_context]
                              :where [:or 
                                      [:= :parent_id id]
                                      [:= :child_id id]]}))
  (jdbc/execute! db
                 (sql/format {:delete-from [:contexts]
                              :where [:= :id id]})))

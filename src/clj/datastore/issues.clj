(ns datastore.issues
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.get-item :refer [get-item] :rename {get-item get-issue}]))

(defn- delete-date [db issue-id]
  (jdbc/execute! db
                 (sql/format {:delete-from [:events]
                              :where [:= :issue_id [:inline issue-id]]})))

(defn- insert-date [db issue-id date event_archived?]
  (jdbc/execute! db
                 (sql/format {:insert-into [:events]
                              :columns     [:issue_id :date :inserted_at :updated_at :archived]
                              :values      [[[:inline issue-id]
                                             [:inline date]
                                             [:raw "NOW()"]
                                             [:raw "NOW()"]
                                             [:inline event_archived?]]]})))

(defn- update-issue* [db {:keys [id title short_title tags data]}]
  (jdbc/execute-one! db
                     (sql/format {:update [:issues]
                                  :where  [:= :id [:inline id]]
                                  :set    {:title       [:inline title]
                                           :short_title [:inline short_title]
                                           :tags        [:inline tags]
                                           :data        [:inline (json/generate-string
                                                                  ;; TODO review
                                                                  (or data {}))]
                                           :updated_at  [:raw "NOW()"]}})
                     {:return-keys true}))

(defn- delete-related-issues [db id]
  (jdbc/execute! db (sql/format {:delete-from [:issue_issue]
                                 :where       [:or
                                               [:= :left_id [:inline id]]
                                               [:= :right_id [:inline id]]]})))

(defn relate-issues [db id related-issues-ids]
  (doall
   (for [related-issue-id related-issues-ids]
     (jdbc/execute! db (sql/format {:insert-into [:issue-issue]
                                    :columns     [:left_id :right_id]
                                    :values      [[[:inline id] [:inline related-issue-id]]
                                                  [[:inline related-issue-id] [:inline id]]]})))))

(defn update-issue [db {:keys [issue related-issues-ids]}]
  (let [{:keys [date id event_archived?]} issue]
    (delete-date db id)
    (delete-related-issues db id)
    (relate-issues db id related-issues-ids)
    (update-issue* db issue)
    (when date
      (insert-date db id date event_archived?))
    (get-issue db issue)))

(defn update-issue-description [db {:keys [id description] :as issue}]
  (jdbc/execute-one! db
                     (sql/format {:update [:issues]
                                  :set    {:description [:inline description]
                                           :updated_at [:raw "NOW()"]}
                                  :where  [:= :id [:inline id]]}))
  (get-issue db issue))

(defn delete-issue [db {:keys [id]}]
  (delete-date db id)
  (jdbc/execute! db (sql/format {:delete-from [:collections]
                                 :where [:= :item_id [:inline id]]}))
  (jdbc/execute! db (sql/format {:delete-from [:issue_issue]
                                 :where [:or
                                         [:= :left_id [:inline id]]
                                         [:= :right_id [:inline id]]]}))
  (jdbc/execute! db (sql/format {:delete-from [:issues]
                                 :where [:= :id [:inline id]]})))

(defn reprioritize-issue [db {:keys [id]}]
  (jdbc/execute! db (sql/format {:update [:issues]
                                 :set {:updated_at [:raw "NOW()"]}
                                 :where [:= :id [:inline id]]})))

(defn link-issue-contexts [db selected-issue link-issue-contexts]
  (jdbc/execute! db (sql/format {:delete-from [:collections]
                                 :where [:= :item_id [:inline (:id selected-issue)]]}))
  (doall (for [context-id link-issue-contexts]
           (jdbc/execute! db (sql/format {:insert-into [:collections]
                                          :columns [:item_id :container_id]
                                          :values [[[:inline (:id selected-issue)]
                                                    [:inline context-id]]]}))))
  (get-issue db selected-issue))

(defn- create-new-issue! [db title short_title]
  (:issues/id (jdbc/execute-one!
               db
               (sql/format {:insert-into [:issues]
                            :columns     [:inserted_at
                                          :updated_at
                                          :title
                                          :short_title]
                            :values      [[[:raw "NOW()"]
                                           [:raw "NOW()"]
                                           title
                                           short_title]]})

               {:return-keys true})))

(defn- insert-issue-relations! [db values]
  (jdbc/execute! db
                 (sql/format {:insert-into [:collections]
                              :columns     [:container_id :item_id]
                              :values      values})))

(defn new-issue [db 
                 {title :title} 
                 context-id 
                 selected-secondary-contexts-set
                 split-short-title?]
  (let [parts       (if split-short-title? (str/split title #"\|") (list title))
        title       (if (= 1 (count parts))
                      (first parts)
                      (second parts))
        short_title (if (= 1 (count parts))
                      ""
                      (first parts))
        issue-id    (create-new-issue! db title short_title)
        values      (vec (doall
                          (map (fn [ctx-id]
                                 [[:inline ctx-id]
                                  [:inline issue-id]])
                               (conj selected-secondary-contexts-set context-id))))]
    (insert-issue-relations! db values)
    (get-issue db {:id issue-id})))

(defn link-issue [db issue-id related-issue-id]
  (let [issue (get-issue db {:id issue-id})
        related-issues-ids (seq (set (remove #{issue-id} (conj (map :id (:related_issues issue)) related-issue-id))))]
    (delete-related-issues db issue-id)
    (relate-issues db issue-id related-issues-ids)))

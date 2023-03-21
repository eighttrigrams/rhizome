(ns datastore.issues
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.helpers
             :refer [un-namespace-keys simplify-date]]))

(defn- join-contexts [issue] ;; TODO dedup with search/join-contexts
  (-> issue
      (dissoc :context_ids)
      (dissoc :context_titles)
      (assoc :contexts
             (zipmap (.getArray (:context_ids issue))
                     (.getArray (:context_titles issue))))))

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

(defn- update-issue* [db {:keys [id title short_title tags]}]
  (jdbc/execute-one! db
                     (sql/format {:update [:issues]
                                  :where  [:= :id [:inline id]]
                                  :set    {:title       [:inline title]
                                           :short_title [:inline short_title]
                                           :tags        [:inline tags]
                                           :updated_at  [:raw "NOW()"]}})
                     {:return-keys true}))

(defn- join-related-issues [db issue]
  (-> issue
      (dissoc :related_issues_ids)
      (dissoc :related_issues_titles)
      (assoc :related_issues
             (doall 
               (map 
                (fn [[id title]]
                  (let [query {:select [:issues.*
                                        [[:array_agg :contexts.id] :context_ids]
                                        [[:array_agg :contexts.title] :context_titles]]
                               :from   [:issues]
                               :join   [:context_issue [:= :issues.id :context_issue.issue_id]
                                        :contexts [:= :context_issue.context_id :contexts.id]]
                               :group-by [:issues.id]
                               :where  [:= :issues.id [:inline id]]}
                        result (-> (jdbc/execute! db (sql/format query))
                                   first
                                   join-contexts)]
                    {:id id
                     :title :title
                     :contexts (:contexts result)}))
                (zipmap (.getArray (:related_issues_ids issue))
                        (.getArray (:related_issues_titles issue))))))))

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

(defn- issues-query [id]
  {:select   [:issues.*
              {:select :date
               :from   [:events]
               :where  [:= :events.issue_id :issues.id]}
              {:select [[:archived :event_archived?]]
               :from   [:events]
               :where  [:= :events.issue_id :issues.id]}
              [[:array_agg :related_issues.id] :related_issues_ids]
              [[:array_agg :related_issues.title] :related_issues_titles]
              [[:array_agg :contexts.id] :context_ids]
              [[:array_agg :contexts.title] :context_titles]]
   :from     [:issues]
   :join     [:issue_issue [:= :issues.id :issue_issue.left_id]
              [:issues :related_issues] [:= :related_issues.id :issue_issue.right_id]
              :context_issue [:= :issues.id :context_issue.issue_id]
              :contexts [:= :context_issue.context_id :contexts.id]]
   :where    [:= :issues.id [:inline id]]
   :group-by [:issues.id]
   :order-by [[:issues.important :desc] [:issues.updated_at :desc]]}) ;; TODO remove

(defn- simple-issues-query [id]
  {:select   [:issues.*
              {:select :date
               :from   [:events]
               :where  [:= :events.issue_id :issues.id]}
              {:select [[:archived :event_archived?]]
               :from   [:events]
               :where  [:= :events.issue_id :issues.id]}
              [[:array_agg :contexts.id] :context_ids]
              [[:array_agg :contexts.title] :context_titles]]
   :from     [:issues]
   :join     [:context_issue [:= :issues.id :context_issue.issue_id]
              :contexts [:= :context_issue.context_id :contexts.id]]
   :where    [:= :issues.id [:inline id]]
   :group-by [:issues.id] ;; TODO remove
   :order-by [[:issues.important :desc] [:issues.updated_at :desc]]}) ;; TODO remove

(defn- get-issue-with-related-issues [db id]
  (when-let [result (-> id
                        issues-query
                        sql/format
                        (#(jdbc/execute-one! db % {:return-keys true})))]
    (join-related-issues db result)))

(defn get-issue [db {:keys [id]}]
  (-> (if-let [issue (get-issue-with-related-issues db id)]
        issue
        ;; TODO explain when this does happen
        (-> id
            simple-issues-query
            sql/format
            (#(jdbc/execute-one! db % {:return-keys true}))))
      un-namespace-keys
      join-contexts
      simplify-date
      (dissoc :searchable)))

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
  (jdbc/execute! db (sql/format {:delete-from [:context_issue]
                                 :where [:= :issue_id [:inline id]]}))
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

(defn mark-issue-important [db {:keys [id important] :as selected-issue}]
  (jdbc/execute! db (sql/format {:update [:issues]
                                 :set {:important (not important)}
                                 :where [:= :id [:inline id]]}))
  (get-issue db selected-issue))

(defn link-issue-contexts [db selected-issue link-issue-contexts]
  (jdbc/execute! db (sql/format {:delete-from [:context_issue]
                                 :where [:= :issue_id [:inline (:id selected-issue)]]}))
  (doall (for [context-id link-issue-contexts]
           (jdbc/execute! db (sql/format {:insert-into [:context_issue]
                                          :columns [:issue_id :context_id]
                                          :values [[[:inline (:id selected-issue)]
                                                    [:inline context-id]]]}))))
  (get-issue db selected-issue))

(defn new-issue [db {title :title} context-id selected-secondary-contexts-ids]
  (let [parts       (str/split title #"\|")
        title       (if (= 1 (count parts))
                      (first parts)
                      (second parts))
        short_title (if (= 1 (count parts))
                      ""
                      (first parts))
        issue-id    (:issues/id (jdbc/execute-one!
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

                                 {:return-keys true}))
        values      (vec (doall
                          (map (fn [ctx-id]
                                 [[:inline ctx-id]
                                  [:inline issue-id]])
                               (conj selected-secondary-contexts-ids context-id))))]
    (jdbc/execute! db
                   (sql/format {:insert-into [:context_issue]
                                :columns     [:context_id :issue_id]
                                :values      values}))
    (get-issue db {:id issue-id})))

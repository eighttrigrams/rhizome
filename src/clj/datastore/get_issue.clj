(ns datastore.get-issue
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.issues.common :as common]))

(defn- related-issues-query [id]
  {:select [:issues.*
            [[:array_agg :issues_o.id] :context_ids]
            [[:array_agg :issues_o.title] :context_titles]
            {:select :date
             :from   [:events]
             :where  [:= :events.issue_id :issues.id]}]
   :from   [:issues]
   :join   [:collections [:= :issues.id :collections.item_id]
            [:issues :issues_o] [:= :collections.container_id :issues_o.id]]
   :group-by [:issues.id]
   :where  [:= :issues.id [:inline id]]})

(defn- perform-query-and-post-process [query db]
  (-> query
      sql/format
      (#(jdbc/execute-one! db % {:return-keys true}))
      common/post-process))

(defn- get-related-issue [db id]
  (-> id
      related-issues-query
      (perform-query-and-post-process db)))

(defn- join-related-issues [db issue]
  (-> issue
      (dissoc :related_issues_ids)
      (assoc :related_issues
             (->> issue
                  :related_issues_ids
                  .getArray
                  (map #(get-related-issue db %))
                  set))))

(defn- basic-issues-query [id]
  {:select   [:issues.*
              {:select :date
               :from   [:events]
               :where  [:= :events.issue_id :issues.id]}
              {:select [[:archived :event_archived?]]
               :from   [:events]
               :where  [:= :events.issue_id :issues.id]}]
   :from     [:issues]
   :where    [:= :issues.id [:inline id]]
   :group-by [:issues.id] ;; TODO remove
   :order-by [[:issues.updated_at :desc]]})

(defn- add-in-collections [query]
  (-> (if (:join query) query (assoc query :join []))
      (update :select conj
              [[:array_agg :issues_o.id] :context_ids]
              [[:array_agg :issues_o.title] :context_titles])
      (update :join conj
              :collections [:= :issues.id :collections.item_id]
              [:issues :issues_o] [:= :collections.container_id :issues_o.id])))

(defn- add-in-relations [query]
  (-> (if (:join query) query (assoc query :join []))
      (update :select conj [[:array_agg :related_issues.id] :related_issues_ids])
      (update :join conj
              :issue_issue [:= :issues.id :issue_issue.left_id]
              [:issues :related_issues] [:= :related_issues.id :issue_issue.right_id])))

(defn- issues-query [id skip-relations?]
  (cond-> (basic-issues-query id)
    true (add-in-collections)
    (not skip-relations?) (add-in-relations)))

(defn- get-issue-with-related-issues [db id]
  (when-let [result (-> id
                        (issues-query false)
                        sql/format
                        (#(jdbc/execute-one! db % {:return-keys true})))]
    (join-related-issues db result)))

(defn- get-issue-without-related-issues [db id]
  (-> id
      (issues-query true)
      sql/format
      (#(jdbc/execute-one! db % {:return-keys true}))))

(defn get-issue
  "Gets an issue, including related issues.
   
   {:id 123
    :title \"some-title-1\"
    :contexts {223 \"some-context-title-1\"}
    :related_issues '({:id 124
                       :title \"some-title-2\"
                       :contexts {224 \"some-context-title\"}})
   }
   "
  [db {:keys [id]}]
  (try
    (-> (if-let [issue (get-issue-with-related-issues db id)]
          issue
        ;; if there are no related issues, the former query returns an empty result set
          (get-issue-without-related-issues db id))
        common/post-process)
    (catch java.lang.Exception e
      (prn "get-issue-----" e)
      (throw e))))

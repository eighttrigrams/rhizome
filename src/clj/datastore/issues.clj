(ns datastore.issues
  (:require [cambium.core :as log]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.get-item :refer [get-item update-item] :rename {get-item get-issue}]))

(defn delete-date [db issue-id]
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
    (update-item db issue :issue)
    (when date
      (insert-date db id date event_archived?))
    (get-issue db issue)))

(defn update-issue-simple [db issue]
  (update-item db issue :issue) 
  (get-issue db issue))

(defn update-issue-description [db {:keys [id description] :as issue}]
  (jdbc/execute-one! db
                     (sql/format {:update [:issues]
                                  :set    {:description [:inline description]
                                           :updated_at [:raw "NOW()"]}
                                  :where  [:= :id [:inline id]]}))
  (get-issue db issue))

(defn reprioritize-issue [db {:keys [id]}]
  (jdbc/execute! db (sql/format {:update [:issues]
                                 :set {:updated_at [:raw "NOW()"]}
                                 :where [:= :id [:inline id]]})))

(defn- create-new-issue! [db title short_title suppress-digit-check?]
  (:issues/id (jdbc/execute-one!
               db
               (sql/format {:insert-into [:issues]
                            :columns     [:inserted_at
                                          :updated_at
                                          :updated_at_ctx
                                          :title
                                          :short_title]
                            :values      [[[:raw "NOW()"]
                                           [:raw "NOW()"]
                                           [:raw "NOW()"]
                                           title
                                           (if (and (not suppress-digit-check?)
                                                    (boolean (re-find #"\d" short_title)))
                                             (do 
                                               (log/error (str "Can't insert short_title due to it containing digit: " short_title))
                                               "")
                                             short_title)]]})

               {:return-keys true})))

(defn- insert-issue-relations! [db values]
  (jdbc/execute! db
                 (sql/format {:insert-into [:collections]
                              :columns     [:container_id :item_id]
                              :values      values})))

(defn new-issue 
  ([db title short-title context-ids-set] (new-issue db title short-title context-ids-set {}))
  ([db 
    title
    short-title
    context-ids-set
    {:keys [suppress-digit-check?]}]
   (when-not (seq context-ids-set) 
     (throw (Exception. "won't create a new-issue when no contexts")))
   (let [issue-id (create-new-issue! db title short-title suppress-digit-check?)
         values   (vec (doall
                        (map (fn [ctx-id]
                               [[:inline ctx-id]
                                [:inline issue-id]])
                             context-ids-set)))]
     (insert-issue-relations! db values)
     (get-issue db {:id issue-id}))))

(defn link-issue [db issue-id related-issue-id]
  (let [issue (get-issue db {:id issue-id})
        related-issues-ids (seq (set (remove #{issue-id} (conj (map :id (:related_issues issue)) related-issue-id))))]
    (delete-related-issues db issue-id)
    (relate-issues db issue-id related-issues-ids)))

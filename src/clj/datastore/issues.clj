(ns datastore.issues
  (:require [cambium.core :as log]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.issues.common :as issues.common]
            [datastore.items :refer [get-item update-item] :rename {get-item get-issue}]))

(defn update-issue [db {:keys [issue]}]
  (let [{:keys [date id archived]} issue]
    (issues.common/delete-date db id)
    (update-item db issue :issue)
    (when date
      (issues.common/insert-date db id date archived))
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

(ns repository.insertion.common
  (:require datastore
            [datastore.items :as items]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(defn get-item-or-throw-error [db title]
  (let [id (:id (items/get-item-by-title db {:title title}))
        _ (when-not id (throw (Exception. (str "no id for " title))))]
    id))

(defn insert-item 
  [db title short-title context-ids-set resource-links]
  (let [issue    (datastore/new-issue db 
                                      title
                                      short-title
                                      context-ids-set)
        contexts (doall (->> (jdbc/execute! db (sql/format {:select [:id :short_title :title]
                                                            :from   [:issues]
                                                            :where  [:in :issues.id 
                                                                     [:inline (seq context-ids-set)]]}))
                             (map (fn [{:issues/keys [id short_title title]}]
                                    [id (if (seq short_title) short_title title)]))
                             (into {})))
        issue    (datastore/update-issue db 
                                         {:issue (update issue :data 
                                                         (fn [data] 
                                                           (assoc data 
                                                                  :resource-links resource-links
                                                                  :contexts contexts)))
                                          :related-issues-ids '()})]
    issue))

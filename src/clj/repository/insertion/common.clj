(ns repository.insertion.common
  (:require [et.vp.ds :as datastore]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(defn get-item-or-throw-error [db title]
  (let [id (:id (datastore/get-item-by-title db {:title title}))
        _ (when-not id (throw (Exception. (str "no id for " title))))]
    id))

(defn insert-item 
  [db title short-title context-ids-set resource-links]
  (let [item    (datastore/new-issue db 
                                      title
                                      short-title
                                      context-ids-set
                                     nil)
        contexts (doall (->> (jdbc/execute! db (sql/format {:select [:id :short_title :title]
                                                            :from   [:issues]
                                                            :where  [:in :issues.id 
                                                                     [:inline (seq context-ids-set)]]}))
                             (map (fn [{:issues/keys [id short_title title]}]
                                    [id {:title (if (seq short_title) short_title title)
                                         :show-badge? true}]))
                             (into {})))
        item    (datastore/update-item db 
                                        (update item :data 
                                                (fn [data] 
                                                  (assoc data 
                                                         :resource-links resource-links
                                                         :contexts contexts))))]
    item))

(ns repository.insertion.common
  (:require datastore
            [datastore.get-item :as get-item]))

(defn get-item-or-throw-error [db title]
  (let [id (:id (get-item/get-item-by-title db {:title title}))
        _ (when-not id (throw (Exception. (str "no id for " title))))]
    id))

(defn insert-item 
  [db title short-title context-ids-set resource-links]
  (let [issue (datastore/new-issue db 
                                   title
                                   short-title
                                   context-ids-set)
          issue (datastore/update-issue db 
                                        {:issue              (update issue :data 
                                                                     (fn [data] (assoc data :resource-links resource-links)))
                                         :related-issues-ids '()})]
    issue))

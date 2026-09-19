(ns et.rz.hub.repository.insertion.common
  (:require [et.rz.hub.ds :as datastore]
            [et.rz.hub.ds.helpers :as helpers]
            [et.rz.hub.db :as db]
            [honey.sql :as sql]))

(defn get-item-or-throw-error
  [db title]
  (let [id (:id (datastore/get-item-by-title db {:title title}))
        _ (when-not id (throw (Exception. (str "no id for " title))))]
    id))

(defn insert-item
  "All callers are scrapers, hence the source default."
  ([db title short-title context-ids-set resource-links]
   (insert-item db title short-title context-ids-set resource-links "scraper"))
  ([db title short-title context-ids-set resource-links source]
   (let [item (datastore/new-item db title short-title context-ids-set nil source)
         contexts (doall (->> (db/execute! db
                                           (sql/format {:select [:id :short_title :title
                                                                 :is_context]
                                                        :from [:items]
                                                        :where [:in :items.id
                                                                [:inline (seq context-ids-set)]]}))
                              (map (fn [{:items/keys [id short_title title is_context]}]
                                     [id
                                      {:title (if (seq short_title) short_title title)
                                       :is-context? (helpers/int->bool is_context)
                                       :show-badge? true}]))
                              (into {})))
         item (datastore/update-item db
                                     (update item
                                             :data
                                             (fn [data]
                                               (assoc data
                                                 :resource-links resource-links
                                                 :contexts contexts)))
                                     source)]
     item)))

(ns datastore.issues.common
  (:require [cheshire.core :as json]
            [datastore.helpers
             :refer [un-namespace-keys simplify-date]]))

(defn- parse-data [context]
  (if (:data context)
    (update context :data #(json/parse-string (.toString %) true))
    context))

(defn- update-contexts [item]
  (update-in item [:data :contexts] 
             (fn [contexts]
               (into {} 
                     (map (fn [[k v]]
                            [(Integer/parseInt (name k)) (if (map? v) v
                                                             {:title       v
                                                              :show-badge? true})])
                          contexts)))))

(comment
  (update-contexts {:data {:contexts {"123" "Name"
                                      "456" {:title "Name" :show-badge? true}}}}))

(defn post-process-simple [query-result]
  (-> query-result
      un-namespace-keys
      simplify-date
      parse-data
      update-contexts
      (dissoc :searchable)))

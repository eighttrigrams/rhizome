(ns datastore.issues.common
  (:require [cheshire.core :as json]
            [datastore.helpers
             :refer [un-namespace-keys simplify-date]]))

(defn- parse-data [context]
  (if (:data context)
    (update context :data #(json/parse-string (.toString %) true))
    context))

(defn post-process-simple [query-result]
  (-> query-result
      un-namespace-keys
      simplify-date
      parse-data
      (#(update-in % [:data :contexts] 
                   (fn [contexts]
                     (into {} 
                           (map (fn [[k v]] [(Integer/parseInt (name k)) v]) contexts)))))
      (dissoc :searchable)))

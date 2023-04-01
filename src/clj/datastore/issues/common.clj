(ns datastore.issues.common
  (:require [datastore.helpers
             :refer [un-namespace-keys simplify-date]]))

(defn- join-contexts [issue]
  (-> issue
      (dissoc :context_ids)
      (dissoc :context_titles)
      (assoc :contexts
             (zipmap (.getArray (:context_ids issue))
                     (.getArray (:context_titles issue))))))

(defn post-process [query-result]
  (-> query-result
      un-namespace-keys
      join-contexts
      simplify-date
      (dissoc :searchable)))

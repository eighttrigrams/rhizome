(ns datastore.issues.common
  (:require [cheshire.core :as json]
            [datastore.helpers
             :refer [un-namespace-keys simplify-date]]))

(defn- join-contexts [issue]
  (-> issue
      (dissoc :context_ids)
      (dissoc :context_titles)
      (assoc :contexts
             (zipmap (.getArray (:context_ids issue))
                     (.getArray (:context_titles issue))))))

;; TODO dedup with datastore.contexts.core/parse-data
(defn- parse-data [context]
  (if (:data context)
    (update context :data #(json/parse-string (.toString %) true))
    context))

;; TODO try unify with datastore.contexts.core/post-process
(defn post-process [query-result]
  (-> query-result
      un-namespace-keys
      join-contexts
      simplify-date
      parse-data
      (dissoc :searchable)))

;; TODO dedup with fn above
(defn post-process-without-join-contexts [query-result]
  (-> query-result
      un-namespace-keys
      simplify-date
      parse-data
      (#(dissoc % :searchable))
      (dissoc :searchable)))

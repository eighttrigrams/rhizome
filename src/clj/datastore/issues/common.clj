(ns datastore.issues.common
  (:require [cheshire.core :as json]
            [datastore.helpers
             :refer [un-namespace-keys simplify-date]]))

(defn- join-contexts [issue]
  (prn "join-contexts" issue)
  (-> issue
      (dissoc :context_ids)
      (dissoc :context_titles)
      (assoc :contexts
             (zipmap (.getArray (:context_ids issue))
                     (.getArray (:context_titles issue))))))

;; TODO dedup with datastore.contexts.core/parse-data
(defn- parse-data [context]
  (prn "--->" context)
  (if (:data context)
    (update context :data #(json/parse-string (.toString %) true))
    context))

;; TODO try unify with datastore.contexts.core/post-process
(defn post-process [query-result]
  (prn "query-rsult" query-result)
  (-> query-result
      un-namespace-keys
      join-contexts
      simplify-date
      parse-data
      ((fn [what] (prn "what" what) what))
      (#(dissoc % :searchable))
      (dissoc :searchable)))

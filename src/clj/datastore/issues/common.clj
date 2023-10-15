(ns datastore.issues.common
  (:require [cheshire.core :as json]
            [datastore.helpers
             :refer [un-namespace-keys simplify-date]]))

(defn- join-contexts [issue]
  (let [contexts (zipmap (zipmap (.getArray (:context_ids issue))
                                 (.getArray (:context_titles issue)))
                         (.getArray (:context_short_titles issue)))
        contexts (into {} (map (fn [[[id title] short-title]]
                                 [id (if (and short-title
                                                (not= "" short-title))
                                       short-title
                                       title)]
                                 ) contexts))
        contexts (dissoc contexts nil)
        result
        (-> issue
            (dissoc :context_ids)
            (dissoc :context_titles)
            (dissoc :context_short_titles)
            (assoc :contexts contexts))]
    (prn "result" contexts)
    result))

(defn- parse-data [context]
  (if (:data context)
    (update context :data #(json/parse-string (.toString %) true))
    context))

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

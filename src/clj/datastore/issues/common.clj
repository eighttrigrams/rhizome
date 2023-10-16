(ns datastore.issues.common
  (:require [cheshire.core :as json]
            [datastore.helpers
             :refer [un-namespace-keys simplify-date]]))

(defn- join-contexts [issue]
  (let [contexts (-> (into {}
                           (map (fn [id title short_title]
                                  [id (if (and short_title
                                               (not= "" short_title))
                                        short_title
                                        title)])
                                (.getArray (:context_ids issue))
                                (.getArray (:context_titles issue))
                                (.getArray (:context_short_titles issue))))
                     (dissoc nil))]
    (-> issue
        (dissoc :context_ids)
        (dissoc :context_titles)
        (dissoc :context_short_titles)
        (assoc :contexts contexts))))

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

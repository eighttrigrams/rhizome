(ns datastore.contexts.core
  (:require [cheshire.core :as json]
            [datastore.helpers
             :refer [un-namespace-keys]]))

;; TODO unify with datastore.issues.common/parse-data
(defn- parse-data [context]
  (if (:data context)
    (update context :data #(json/parse-string (.toString %) true))
    context))

;; TODO unify with datastore.issues.common/post-process
(defn post-process [context-as-retrieved]
  (-> context-as-retrieved
      un-namespace-keys
      parse-data
      (#(dissoc % :searchable))))

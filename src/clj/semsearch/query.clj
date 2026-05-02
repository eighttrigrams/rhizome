(ns semsearch.query
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [cambium.core :as log]
            [semsearch.embedder :as embedder]
            [et.vp.ds :as datastore]))

(defn- context-filter-sql [ctx-ids]
  (str/join " AND "
    (repeat (count ctx-ids)
            "items_vec.item_id IN (SELECT target_id FROM relations WHERE owner_id = ?)")))

(defn search-related-items-vector
  "kNN over items_vec, scoped to items related to selected-id (AND every
   id in secondary-context-ids). Items without an embedding row are
   excluded. Returns fully-enriched items, ordered by cosine distance."
  [db q selected-id {:keys [secondary-context-ids limit]}]
  (when (str/blank? q)
    (throw (IllegalArgumentException. "vector search requires non-empty q")))
  (let [qvec (embedder/embed-text q)
        qjson (embedder/vec->json qvec)
        ctx-ids (cons selected-id (or secondary-context-ids []))
        k (or limit 20)
        sql (str "SELECT items_vec.item_id, items_vec.distance "
                 "FROM items_vec "
                 "WHERE items_vec.embedding MATCH ? AND k = ? "
                 "AND " (context-filter-sql ctx-ids) " "
                 "ORDER BY items_vec.distance")
        params (into [sql qjson k] ctx-ids)
        rows (jdbc/execute! db params)]
    (log/info {:vector-search {:selected-id selected-id
                               :ctx-ids ctx-ids
                               :hits (count rows)}})
    (->> rows
         (map :items_vec/item_id)
         (keep #(datastore/get-item db {:id %})))))

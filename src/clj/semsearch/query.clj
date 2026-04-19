(ns semsearch.query
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [cambium.core :as log]
            [semsearch.embedder :as embedder]
            [et.vp.ds :as datastore]))

(defn- context-filter-sql [ctx-ids]
  (str/join " AND "
    (repeat (count ctx-ids)
            "items.id IN (SELECT target_id FROM relations WHERE owner_id = ?)")))

(defn search-related-items-vector
  "kNN over the `embedding` column, scoped to items related to `selected-id`
  (AND optionally every id in `secondary-context-ids`). `q` is the text to
  embed for the query. Items with NULL embedding are excluded. Returns
  fully-enriched items, ordered by cosine distance (closest first)."
  [db q selected-id {:keys [secondary-context-ids limit]}]
  (when (str/blank? q)
    (throw (IllegalArgumentException. "vector search requires non-empty q")))
  (let [qvec (embedder/embed-text q)
        qvec-str (embedder/vec->pg-literal qvec)
        ctx-ids (cons selected-id (or secondary-context-ids []))
        sql (str "SELECT items.id "
                 "FROM items "
                 "WHERE items.embedding IS NOT NULL "
                 "AND " (context-filter-sql ctx-ids) " "
                 "ORDER BY items.embedding <=> ?::vector "
                 "LIMIT ?")
        params (into [] (concat ctx-ids [qvec-str (or limit 20)]))
        rows (jdbc/execute! db (into [sql] params))]
    (log/info {:vector-search {:selected-id selected-id
                               :ctx-ids ctx-ids
                               :hits (count rows)}})
    (->> rows
         (map :items/id)
         (keep #(datastore/get-item db {:id %})))))

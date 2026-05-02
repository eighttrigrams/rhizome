(ns semsearch.query
  (:require [clojure.string :as str]
            [semsearch.embedder :as embedder]
            [et.vp.ds.search :as search]))

(defn search-related-items-vector
  "Vector-ranked retrieval. Reuses search/search-related-items so all the
   regular relational filters (selected-secondary-contexts,
   secondary-contexts-inverted, secondary-contexts-unassigned-selected,
   search-mode, description-filter) continue to apply; the only difference
   is an extra INNER JOIN on items_vec and ORDER BY cosine distance to the
   embedded query. Items without an items_vec row are excluded."
  [db q selected-id {:keys [limit] :as opts}]
  (when (str/blank? q)
    (throw (IllegalArgumentException. "vector search requires non-empty q")))
  (let [qjson (embedder/vec->json (embedder/embed-text q))]
    (search/search-related-items
      db "" selected-id
      (assoc opts :vector-qjson qjson)
      {:limit (or limit 100)})))

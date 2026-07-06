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
  (let [qjson (embedder/vec->json (embedder/embed-query q))]
    (search/search-related-items
      db "" selected-id
      (assoc opts :vector-qjson qjson)
      {:limit (or limit 100)})))

(def ^:private empty-threshold-result
  {:items [] :vector-threshold nil :vector-max-similarity nil :vector-min-similarity nil})

;; Tolerance for the top/bottom slider extremes. The <input type=range> serializes
;; its value with less precision than a Java double carries, so "full-left" arrives
;; a hair (~1e-16) ABOVE the true min similarity and "full-right" a hair off the
;; max. This eps (far below the slider's 1e-3 step, far above float error) lets the
;; guards recognise the extremes and include the boundary item(s).
(def ^:private similarity-epsilon 1e-9)

(defn search-related-items-vector-threshold
  "Blue-mode vector retrieval. Keeps the caller's ORIGINAL ordering (no
   re-rank), annotates each related item with cosine :similarity, and
   filters — in SQL — to items whose similarity >= threshold.

   `threshold` nil snaps to the query's max similarity, so only the top
   item(s) (including exact ties) come back.

   The two slider extremes are float-guarded so no boundary item drops out to
   rounding: at/above max similarity the cutoff is the raw min distance (only
   the top tie), and at/below min similarity it is the raw max distance (all
   embedded items). Both use the SQL-computed distance directly rather than
   round-tripping through 1 - (1 - x), which is not exact in floating point.

   Embeds q exactly once, then runs two SQL queries sharing that embedding:
   one for the similarity bounds, one for the filtered items. Returns
   {:items [...] :vector-threshold <effective> :vector-max-similarity <max>
    :vector-min-similarity <min>}; blank q or no embedded related items
   short-circuit to an empty result."
  [db q selected-id {:keys [threshold limit] :as opts}]
  (if (str/blank? q)
    empty-threshold-result
    (let [qjson (embedder/vec->json (embedder/embed-query q))
          opts (-> opts (dissoc :threshold :limit) (assoc :vector-qjson qjson))
          {:keys [min_distance max_distance]} (search/vector-similarity-bounds db "" selected-id opts)]
      (if (nil? min_distance)
        empty-threshold-result
        (let [max-sim (- 1 min_distance)
              min-sim (- 1 max_distance)
              max-dist (cond
                         (nil? threshold)                              min_distance
                         (>= threshold max-sim)                        min_distance
                         (<= threshold (+ min-sim similarity-epsilon)) max_distance
                         :else                                         (- 1 threshold))
              items (search/search-related-items-vector-threshold
                      db "" selected-id
                      (assoc opts :vector-max-distance max-dist)
                      {:limit (or limit 100)})]
          {:items items
           :vector-threshold (if (nil? threshold) max-sim threshold)
           :vector-max-similarity max-sim
           :vector-min-similarity min-sim})))))

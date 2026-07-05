(ns et.vp.ds.search.core
  (:require [honey.sql :as sql]
            [clojure.string :as str]))

(def select
  [:items.title :items.short_title :items.sort_idx :items.id :items.data
   ;; TODO make tags optional; i need it only in tracker-mcp
   :items.tags [:items.annotation :item_annotation] :items.is_context :items.inserted_at
   :items.updated_at :items.date :items.hide_in_global_search])

(defn exclusion-clause
  [selected-item-id mode]
  [:not
   [:in :items.id
    (if (= :items mode)
      {:select :relations.target_id
       :from :relations
       :where [:= :relations.owner_id [:inline selected-item-id]]}
      {:select :relations.owner_id
       :from :relations
       :where [:= :relations.target_id selected-item-id]})]])

(defn- sanitize-fts-token
  "FTS5 is fussy about its query syntax. Strip everything that isn't a
   word-character or whitespace, collapse runs of whitespace, then trim."
  [q]
  (-> (or q "")
      (str/replace #"[\"\[\]()|!&':{}*+\-,;]+" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn convert-q-to-fts-query
  "Turn a free-text query into an FTS5 MATCH expression: every token is
   ANDed and prefix-matched. Empty input → nil (caller should skip)."
  [q]
  (let [tokens (->> (str/split (sanitize-fts-token q) #" ")
                    (remove str/blank?))]
    (when (seq tokens)
      (str/join " AND " (map #(str \" % \" "*") tokens)))))

(defn get-search-clause
  "Returns a HoneySQL fragment that constrains items.id to the FTS index
   match, or nil when q is empty."
  [q]
  (when-let [match (convert-q-to-fts-query q)]
    [:in :items.id
     [:raw (str "(SELECT rowid FROM items_fts WHERE items_fts MATCH '"
                (str/replace match "'" "''")
                "')")]]))

(defn search-items
  [q {:keys [selected-item-id all-items? link-context link-item exclude-hidden?] :as _opts}
   {:keys [limit] :as _ctx}]
  (let [exclusion-clause (when (or link-context link-item)
                           (exclusion-clause selected-item-id (if link-item :items :contexts)))]
    (sql/format (merge {:select select
                        :from [:items]
                        :where [:and (get-search-clause q)
                                (when-not all-items? [:= :items.is_context true])
                                (when selected-item-id [:not [:= :items.id selected-item-id]])
                                (when selected-item-id exclusion-clause)
                                (when exclude-hidden?
                                  [:= :items.hide_in_global_search false])]
                        :order-by [[(if all-items? :items.updated_at :items.updated_at_ctx) :desc]]}
                       (when limit {:limit limit})))))

(defn- get-events-exist-clause [search-mode] (when (= 4 search-mode) [:<> :items.date nil]))

(defn- get-description-filter-clause
  [description-filter]
  (case description-filter
    (true :only "only") [:and [:<> :items.description nil] [:not [:= :items.description ""]]]
    (false :no "no") [:or [:= :items.description nil] [:= :items.description ""]]
    nil))

(defn- and-query
  [join-ids unassigned-mode? inverted-mode?]
  (let [r [:in :items.id
           (merge
             {:select :items.id
              :from [:items]
              :join [:relations [:= :items.id :relations.target_id]]
              :group-by :items.id
              :having [:raw (str "COUNT(items.id) = " (if unassigned-mode? 1 (count join-ids)))]}
             (when-not unassigned-mode? {:where [:in :relations.owner_id [:inline join-ids]]}))]]
    (if inverted-mode? [:not r] r)))

(defn- or-partial
  [join-ids]
  {:select :items.id
   :from [:items]
   :join [:relations [:= :items.id :relations.target_id]]
   :where [:in :relations.owner_id [:inline join-ids]]})

(defn- or-query
  [join-ids unassigned-mode?]
  (if unassigned-mode?
    [:and [:not [:in :items.id (or-partial join-ids)]]
     [:in :items.id
      {:select :items.id
       :from [:items]
       :join [:relations [:= :items.id :relations.target_id]]
       :group-by :items.id
       :having [:raw "COUNT(items.id) > 1"]}]]
    [:not [:in :items.id (or-partial join-ids)]]))

(defn- order-by
  [search-mode]
  [(if (= search-mode 5)
     [:items.inserted_at :desc]
     (if (= search-mode 4)
       [:items.date :desc]
       (if (or (= 2 search-mode) (= 3 search-mode))
         [:items.sort_idx (if (= 2 search-mode) :asc :desc)]
         [:items.updated_at (if (= 1 search-mode) :asc :desc)])))])

(defn- related-items-query-map
  "HoneySQL map for related-items retrieval.

   Vector opts (all optional, only meaningful when :vector-qjson is set):
   - :vector-qjson              INNER JOIN items_vec + enable the cosine
                                distance expression (green/blue).
   - :vector-keep-order?        keep the search-mode ordering instead of
                                ranking by cosine distance (blue mode:
                                original order, no re-rank).
   - :vector-select-similarity? add a `1 - cosine_distance AS similarity`
                                column to the projection.
   - :vector-max-distance       only return rows whose cosine distance is
                                <= this value (i.e. similarity >= threshold)."
  [q
   {:keys [selected-item-id join-ids search-mode unassigned-mode? inverted-mode?
           description-filter vector-qjson vector-keep-order? vector-select-similarity?
           vector-max-distance]
    :as _opts} {:keys [limit] :as _ctx}]
  (let [or-mode? (when join-ids inverted-mode?)
        distance [:vec_distance_cosine :items_vec.embedding vector-qjson]
        joins (cond-> [:relations [:= :items.id :relations.target_id]]
                vector-qjson (into [:items_vec [:= :items.id :items_vec.item_id]]))
        ordering (if (and vector-qjson (not vector-keep-order?))
                   [[distance :asc]]
                   (order-by search-mode))
        projection (cond-> (vec (concat select [:relations.annotation]))
                     (and vector-qjson vector-select-similarity?)
                     (conj [[:- 1 distance] :similarity]))]
    (merge {:select projection
            :from :items
            :where [:and
                    (when (or join-ids unassigned-mode?)
                      (if or-mode?
                        (or-query join-ids unassigned-mode?)
                        (and-query join-ids unassigned-mode? inverted-mode?)))
                    (get-search-clause q) (get-events-exist-clause search-mode)
                    (get-description-filter-clause description-filter)
                    [:= :relations.owner_id [:raw selected-item-id]]
                    (when (or (= 2 search-mode) (= 3 search-mode)) [:<> :sort_idx -1])
                    (when (and vector-qjson vector-max-distance)
                      [:<= distance vector-max-distance])]}
           {:order-by ordering}
           (when limit {:limit limit})
           {:join joins})))

(defn search-related-items
  [q opts ctx]
  (sql/format (related-items-query-map q opts ctx)))

(defn vector-similarity-bounds
  "SQL for the MIN and MAX cosine distance over the embedded related items,
   reusing the exact relational filters of the related-items query (so the
   bounds match the set the threshold query filters). Distances map to
   similarities via similarity = 1 - distance; hence min distance -> max
   similarity and max distance -> min similarity. Over an empty set both
   aggregates are NULL."
  [q {:keys [vector-qjson] :as opts}]
  (let [distance [:vec_distance_cosine :items_vec.embedding vector-qjson]]
    (-> (related-items-query-map q (dissoc opts :vector-max-distance) {})
        (assoc :select [[[:min distance] :min_distance] [[:max distance] :max_distance]])
        (dissoc :order-by :limit)
        sql/format)))

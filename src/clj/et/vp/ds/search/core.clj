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

(def max-part-of-level
  "SQLite plans a join with a 64-bit bitmask over its tables, so 64 is all it
   will take in one query -- and a level costs one `relations` alias, with the
   `items` join on top. Nothing in a rhizome is filed 63 deep; the clamp is here
   so a level arriving from somewhere other than the strip's stepper answers with
   the deepest expressible level (empty, at that depth) instead of a database
   error.

   Public because /api refuses a level past it rather than clamping -- an empty
   list would read as `nothing is filed that deep`, which is a different fact --
   and the refusal has to name the same ceiling this enforces."
  63)

(defn clamp-part-of-level
  "The level a part-of-level query will actually be built at: at least the first,
   at most as deep as one query can express. Callers that have to line something
   up with the answer -- the path columns, say -- ask here rather than repeating
   the arithmetic."
  [level]
  (min max-part-of-level (max 1 (or level 1))))

(defn path-column
  "The alias the n-th step of a row's path is projected under. One column per
   step rather than one string to be split: they are ids, and they stay ids."
  [n]
  (keyword (str "part_of_path_" n)))

(defn- step
  "A column of the `relations` alias carrying the n-th step of a path."
  [n col]
  (keyword (str "r" n "." (name col))))

(defn part-of-level
  "The nodes exactly `level` steps below one whole along the part-of edges — what
   hierarchy mode lists. Level 1 is the parts of the selected item, level 2 the
   parts of those, and so on; a level lists nodes at that depth and no other, so
   the direct children are not among the level-2 rows.

   One `relations` alias per step, joined head to tail, which is why the level
   has to be known before the SQL is formatted rather than being a parameter of
   it. The part-of edges are a DAG, so a node can sit at this depth by more than
   one route; the joins multiply and it comes out once per route, at each place
   it occupies. That is deliberate -- deduplicating would drop one of two
   positions the human deliberately gave the same thing -- and it means the row
   count follows the number of paths rather than the number of nodes, which in a
   DAG can grow with depth without a cycle being involved. Hence the limit, which
   the caller supplies exactly as it does for the ordinary list.

   Only part-of edges are walked: an item merely related to one of these is not
   one of its parts, and leaving those out is the point of the mode.

   The order is the whole path, not the last step of it: the tuple of
   `part_of_sort_idx` from the selected item down to the node, compared
   component by component -- so everything under the first child comes before
   everything under the second, whatever indices are used further down. Within
   each component the rule level 1 has always used holds: the ones carrying an
   index first, ascending, and the ones left unset -- the -1 the column defaults
   to -- after them, rather than ahead of 0 as a plain ascending sort would have
   it. A part nobody placed does not belong in front of every part somebody did.
   It is still listed, though -- it is a part, it just has no place yet. Paths
   that are equal all the way down fall back on the app's usual
   most-recently-touched-first.

   The sibling index projected is the last step's -- the node's place under the
   whole it is directly a part of -- because that is the number the human typed
   into the edit modal for it.

   With `:with-path?` each row also carries the ids it was reached through, one
   column per step. Two rows for the same node are otherwise identical, and in a
   list of items that is all the answer there is to `is this filed twice or did
   the query repeat itself` -- the strip's list is told apart by where a row sits
   and by the badges on it, and a caller reading the rows alone has neither."
  [q {:keys [selected-item-id level with-path?]} {:keys [limit] :as _ctx}]
  (let [level (clamp-part-of-level level)]
    (sql/format
      (merge {:select (vec (concat select
                                   [[(step level :annotation) :annotation]
                                    [(step level :part_of_sort_idx) :part_of_sort_idx]]
                                   (when with-path?
                                     (map (fn [n] [(step n :target_id) (path-column n)])
                                          (range 1 (inc level))))))
              :from [[:relations :r1]]
              :join (-> (into []
                              (mapcat (fn [n]
                                        [[:relations (keyword (str "r" n))]
                                         [:and [:= (step n :owner_id) (step (dec n) :target_id)]
                                          [:= (step n :is_part_of) true]]]))
                              (range 2 (inc level)))
                        (into [:items [:= :items.id (step level :target_id)]]))
              :where [:and [:= (step 1 :owner_id) [:inline selected-item-id]]
                      [:= (step 1 :is_part_of) true] (get-search-clause q)]
              :order-by (-> (into []
                                  (mapcat (fn [n]
                                            [[[:= (step n :part_of_sort_idx) [:inline -1]] :asc]
                                             [(step n :part_of_sort_idx) :asc]]))
                                  (range 1 (inc level)))
                            (conj [:items.updated_at :desc]))}
             (when limit {:limit limit})))))

(defn part-of-depth
  "How many levels the part-of edges below one whole run to: the length of the
   longest path down from it, and 0 when it has no parts at all. The number the
   strip's stepper stops at.

   `UNION`, not `UNION ALL`: this asks how deep, not by how many routes, so a
   node already seen at a depth need not be walked again from a second parent.
   That is what keeps this cheap where part-of-level cannot be -- the walk is
   bounded by nodes times depth rather than by paths, which is the difference
   between polynomial and combinatorial on the same graph.

   Termination rests on acyclicity, which every write already enforces (see
   et.vp.ds.part-of), so there is no depth cap here to make it safe. Nor one to
   make it cheap: the dedup above is that argument, and a cap would be a second
   one that has to be right about how deep a rhizome is allowed to be."
  [selected-item-id]
  (sql/format
    {:with-recursive [[[:below {:columns [:id :depth]}]
                       {:union [{:select [:relations.target_id [[:inline 1] :depth]]
                                 :from :relations
                                 :where [:and [:= :relations.owner_id [:inline selected-item-id]]
                                         [:= :relations.is_part_of true]]}
                                {:select [:relations.target_id
                                          [[:+ :below.depth [:inline 1]] :depth]]
                                 :from :below
                                 :join [:relations [:and [:= :relations.owner_id :below.id]
                                                    [:= :relations.is_part_of true]]]}]}]]
     :select [[[:coalesce [:max :below.depth] [:inline 0]] :depth]]
     :from :below}))

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

(ns et.vp.ds.search
  (:require [cambium.core :as log]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.vp.ds.search.core :as core]
            [et.vp.ds.helpers :refer [un-namespace-keys post-process-base] :as helpers]))

(defn- post-process
  [result]
  (let [{:keys [annotation item_annotation] :as r} (post-process-base result)]
    (cond-> r
      (empty? annotation) (assoc :annotation item_annotation)
      item_annotation (assoc-in [:data :annotation] item_annotation))))

(defn- post-process-contexts
  [item]
  (if (-> item
          :data
          :contexts)
    (update-in item
               [:data :contexts]
               (fn [contexts]
                 (into {}
                       (map (fn [[k v]]
                              (try [(Integer/parseInt (name k)) v]
                                   (catch Exception e
                                     (log/error {:e e :k k :v v :item item}
                                                "whoops while trying to convert to int")
                                     [k v])))
                         contexts))))
    item))

(comment
  (post-process-contexts {:data {:contexts {"123" {:title "Name1" :show-badge? true}
                                            :456 {:title "Name2" :show-badge? true}}}}))

(defn search-items
  [db q {:keys [all-items? link-context link-item] :as opts} ctx]
  (when (:selected-item opts)
    (throw (IllegalArgumentException.
             "Didn't expect 'selected-item' here. Did you mean to pass 'selected-item-id'?")))
  (when (and link-context all-items?)
    (throw (IllegalArgumentException. "Can't combine 'all-items?' and 'link-context'")))
  (when (and link-item (not all-items?))
    (throw (IllegalArgumentException. "Must set 'all-items?' on 'link-item'")))
  (try (->> (core/search-items q opts ctx)
            (jdbc/execute! db)
            (map post-process)
            (map post-process-contexts))
       (catch Exception e
         (log/error (str "error in search/search-items: " e " - param was: " q))
         (throw e))))

(defn find-items-by-ids
  "Look up items by primary id and/or by human-readable id, returning the union.
  Pass only the id categories you actually want matched — empty/missing
  categories skip their column entirely so we don't waste a scan on a field
  the caller doesn't care about."
  [db {:keys [numeric-ids human-readable-ids]}]
  (let [conds (cond-> []
                (seq numeric-ids)
                (conj [:in :items.id (vec numeric-ids)])
                (seq human-readable-ids)
                (conj [:in :items.human_readable_id (vec human-readable-ids)]))]
    (when (seq conds)
      (try (->> (sql/format {:select (conj core/select :items.human_readable_id)
                             :from [:items]
                             :where (into [:or] conds)
                             :order-by [[:items.updated_at_ctx :desc]]})
                (jdbc/execute! db)
                (map post-process)
                (map post-process-contexts))
           (catch Exception e
             (log/error (str "error in search/find-items-by-ids: " e
                             " - numeric: " numeric-ids
                             " - human-readable: " human-readable-ids))
             (throw e))))))

(defn- do-query
  [db formatted-query]
  #_(prn "???" formatted-query)
  (let [items (jdbc/execute! db formatted-query)]
    (log/info (str "count: " (count items)))
    items))

(defn- no-modifiers-selected?
  [{:keys [secondary-contexts-unassigned-selected secondary-contexts-inverted]}]
  (not (or secondary-contexts-inverted secondary-contexts-unassigned-selected)))

(defn- join-ids
  [opts]
  (let [selected-secondary-contexts (:selected-secondary-contexts opts)]
    (when (and (seq selected-secondary-contexts)
               (or (no-modifiers-selected? opts) (:secondary-contexts-inverted opts)))
      selected-secondary-contexts)))

(defn modify
  [opts]
  (cond-> opts
    (and (seq (:selected-secondary-contexts opts))
         (:secondary-contexts-unassigned-selected opts)
         (not (:secondary-contexts-inverted opts)))
      (assoc :secondary-contexts-unassigned-selected nil)))

(defn- ->core-opts
  "Translate a (already modify'd) repository opts map into the shape
   core/related-items-query-map expects. Vector keys pass through untouched
   (nil for non-vector callers -> identical SQL to before)."
  [selected-item-id search-mode opts]
  {:selected-item-id selected-item-id
   :search-mode search-mode
   :unassigned-mode? (:secondary-contexts-unassigned-selected opts)
   :join-ids (join-ids opts)
   :inverted-mode? (:secondary-contexts-inverted opts)
   :description-filter (:description-filter opts)
   :vector-qjson (:vector-qjson opts)
   :vector-keep-order? (:vector-keep-order? opts)
   :vector-select-similarity? (:vector-select-similarity? opts)
   :vector-max-distance (:vector-max-distance opts)})

(defn search-related-items
  "The items to list under the selected item.

   In hierarchy mode that is a different question -- the nodes one level of the
   part-of edges below it, in path order -- so it is a different query rather
   than the usual one with a filter bolted on: none of the intersection
   machinery (secondary contexts, invert, search modes, description filter)
   means anything in a hierarchy, and the mode hides the section that drives it.

   :hierarchy-level rides in with :hierarchy-mode?, and for the same reason:
   both are session state the SPA carries. Absent, it is level 1 -- what the
   mode listed before there were levels at all."
  [db q selected-item-id {:keys [link-item search-mode hierarchy-mode? hierarchy-level] :as opts}
   {:keys [limit] :as ctx}]
  (when link-item
    (throw (IllegalArgumentException. "'link-item' shouldn't be supplied here any longer")))
  (when-not selected-item-id
    (throw (IllegalArgumentException. "selected-context-id must not be nil")))
  (let [opts (modify opts)
        items (do-query db
                        (if hierarchy-mode?
                          (core/part-of-level q
                                              {:selected-item-id selected-item-id
                                               :level hierarchy-level}
                                              ctx)
                          (core/search-related-items q
                                                     (->core-opts selected-item-id search-mode opts)
                                                     ctx)))
        results (->> (seq items)
                     (map post-process)
                     (map post-process-contexts))]
    (when (and limit (> (count results) limit))
      (throw (Exception. "got more results than 'limit' allows. impl broken!")))
    results))

(defn part-of-depth
  "How deep the part-of edges below `selected-item-id` run -- the deepest level
   hierarchy mode has anything to show for this whole, and 0 when it has no
   parts at all. What the strip's stepper needs to know before it offers a step
   down, rather than offering one and answering it with an empty list."
  [db selected-item-id]
  (:depth (un-namespace-keys (jdbc/execute-one! db (core/part-of-depth selected-item-id)))))

(defn search-related-items-vector-threshold
  "Blue-mode retrieval. Same relational filters + INNER JOIN items_vec as
   search-related-items, but keeps the original (search-mode) ordering,
   annotates each row with cosine :similarity, and returns only rows whose
   cosine distance is <= (:vector-max-distance opts) (i.e. similarity above
   the threshold). Requires :vector-qjson in opts."
  [db q selected-item-id {:keys [search-mode] :as opts} {:keys [limit] :as ctx}]
  (when-not selected-item-id
    (throw (IllegalArgumentException. "selected-context-id must not be nil")))
  (let [opts (-> (modify opts)
                 (assoc :vector-keep-order? true :vector-select-similarity? true))
        rows (do-query db (core/search-related-items q (->core-opts selected-item-id search-mode opts) ctx))
        results (->> (seq rows)
                     (map post-process)
                     (map post-process-contexts))]
    (when (and limit (> (count results) limit))
      (throw (Exception. "got more results than 'limit' allows. impl broken!")))
    results))

(defn vector-similarity-bounds
  "Return {:min_distance <d-or-nil> :max_distance <d-or-nil>}: the cosine
   distance extent over the embedded related items (identical filters to
   search-related-items). Both nil when no related item is embedded.
   Requires :vector-qjson in opts."
  [db q selected-item-id {:keys [search-mode] :as opts}]
  (let [opts (modify opts)
        row (jdbc/execute-one! db (core/vector-similarity-bounds q (->core-opts selected-item-id search-mode opts)))]
    (un-namespace-keys row)))

(defn- try-parse [item] (try (Integer/parseInt item) (catch Exception _e nil)))

(defn- pre-process-highlighted-secondary-contexts
  [highlighted-secondary-contexts]
  (->> highlighted-secondary-contexts
       (keep try-parse)))

(defn get-title
  [db {:keys [id]}]
  (-> {:select [:items.title]
       :from [:items]
       :where [:= :items.id [:inline id]]
       :group-by [:items.id]
       :order-by [[:items.updated_at :desc]]}
      sql/format
      (#(jdbc/execute-one! db % {:return-keys true}))
      un-namespace-keys
      :title))

(defn- calc-highlighted
  [db secondary-contexts highlighted-secondary-contexts]
  (reduce (fn [acc val]
            (if (secondary-contexts val)
              (conj acc [val (conj (secondary-contexts val) true)])
              (if-let [title (get-title db {:id val})]
                (conj acc [val [title 0 true]])
                acc)))
    []
    highlighted-secondary-contexts))

(defn- sort-secondary-contexts
  [db highlighted-secondary-contexts secondary-contexts]
  (let [highlighted-secondary-contexts (pre-process-highlighted-secondary-contexts
                                         highlighted-secondary-contexts)
        secondary-contexts (into {} secondary-contexts)
        front (calc-highlighted db secondary-contexts highlighted-secondary-contexts)
        back (->> secondary-contexts
                  (remove (fn [[k _v]] (some #{k} highlighted-secondary-contexts)))
                  (map (fn [[k [val title]]] [k [val title false]])))]
    (concat front (reverse (sort-by #(get-in % [1 1]) back)))))

(defn get-aggregated-contexts
  [db items highlighted-secondary-contexts]
  (->> items
       (map #(get-in % [:data :contexts]))
       (map #(filter (fn [[_id {:keys [show-badge? is-context?]}]] (and show-badge? is-context?))
               %))
       (map seq)
       (apply concat)
       (group-by first)
       (map #(do [(count (second %)) (first (second %))]))
       (sort-by first)
       reverse
       (map (fn [[count [id title]]] [id [title count]]))
       (sort-secondary-contexts db highlighted-secondary-contexts)))

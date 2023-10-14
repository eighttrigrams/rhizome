(ns datastore.search
  (:require [clojure.set :as set]
            [cambium.core :as log]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.issues.common :as common]
            [datastore.get-item :as get-item]))

(defn- remove-some-chars [q]
  (-> q
      (str/replace "(" " ")
      (str/replace ")" " ")
      (str/replace "[" " ")
      (str/replace "]" " ")
      (str/replace "|" " ")
      (str/replace "!" " ")
      (str/replace "&" " ")
      (str/replace "'" " ")
      (str/replace ":" " ")
      (str/replace "{" " ")
      (str/replace "}" " ")
      (str/replace "  " " ")
      (str/replace "  " " ")
      (str/trim)))

(defn- convert-q-to-query-string [q]
  (let [qs
        (str/join " & " (map #(str % ":*") (str/split (remove-some-chars q) #" ")))]
    (if (= ":*" qs)
      "*"
      qs)))

(def all-contexts-query (sql/format {:select :issues.id
                                     :from [:issues]
                                     :where [:= :issues.is_context true]
                                     :order-by [[:updated_at :desc]]
                                     :limit 500}))

(defn- query-string-contexts-query [q]
  (sql/format {:select :issues.id
               :from   [:issues]
               :where [:and
                       [:raw (format "searchable @@ to_tsquery('simple', '%s')"
                                     (convert-q-to-query-string q))]
                       [:= :issues.is_context true]]
               :order-by [[:updated_at :desc]]}))

(defn- ids-query [ids]
  (sql/format
   {:select    [:issues.*
                [[:array_agg :issues_o.id] :context_ids]
                [[:array_agg :issues_o.title] :context_titles]]
    :from      [:issues]
    :where     [:in :issues.id [:inline ids]]
    :left-join [:collections [:= :issues.id :collections.item_id]
                [:issues :issues_o] [:= :collections.container_id :issues_o.id]]
    :group-by [:issues.id]
    :order-by  [[:updated_at :desc]]}))

(defn- filter-contexts [{:keys [link-context selected-context selected-issue]} contexts]
  (if-not link-context
    (remove #(= (:id selected-context) (:id %)) contexts)
    (let [ids-of-contexts-to-remove (conj (set (keys (or (:contexts selected-issue)
                                                         (:contexts selected-context))))
                                          (:id (or selected-issue selected-context)))]
      (remove #(ids-of-contexts-to-remove (:id %)) contexts))))

(defn search-contexts
  [db opts]
  (let [opts (if (string? opts) 
               {:q opts}
               opts)
        {:keys [q]} opts]
    (try
      (->>
       (if (= "" (or q ""))
         (jdbc/execute! db all-contexts-query)
         (jdbc/execute! db (query-string-contexts-query q)))
       (map :issues/id)
       (jdbc/execute! db
                      (ids-query ids))
       (map common/post-process)
       (filter-contexts opts))
      (catch Exception e
        (log/error (str "error in search-contexts: " (.getMessage e) " - param was: " q))
        (throw e)))))

(defn- fetch-ids [ds q selected-context events-view]
  (let [selected-context (when (:id selected-context) selected-context)
        search-clause       (if (not= "" q)
                              [:raw (format "searchable @@ to_tsquery('simple', '%s')" 
                                            (convert-q-to-query-string q))] 
                              [:=])
        join-clause         (if selected-context
                              [:collections [:= :issues.id :collections.item_id]]
                              [])
        join-where-clause   (if selected-context
                              [:= :collections.container_id (:id selected-context)]
                              [:=])
        exists-clause       (if (not= 0 events-view)
                              [:exists {:select [:events.id]
                                        :from   [:events]
                                        :where  [:and
                                                 [:= :events.issue_id :issues.id]
                                                 [:not= :events.archived [:inline (= 1 events-view)]]]}]
                              [:=])
        formatted-query (sql/format (merge
                                     {:select   [:issues.id]
                                      :from     [:issues]
                                      :order-by [[:updated_at :desc]]
                                      :join     join-clause
                                      :where    [:or
                                                 [:and
                                                  exists-clause
                                                  join-where-clause
                                                  search-clause]]}
                                     (when (and (= "" q)
                                                (not selected-context)
                                                (= 0 events-view))
                                       {:limit 500})))]
    (jdbc/execute! ds formatted-query)))

(defn- issues-query [ids]
  {:select   [:issues.*
              {:select :date
               :from   [:events]
               :where  [:= :events.issue_id :issues.id]}
              [[:array_agg :issues_o.id] :context_ids]
              [[:array_agg :issues_o.title] :context_titles]]
   :from     [:issues]
   :join     [:collections [:= :issues.id :collections.item_id]
              [:issues :issues_o] [:= :collections.container_id :issues_o.id]]
   :where    [:in :issues.id [:inline ids]]
   :group-by [:issues.id]
   :order-by [[:issues.updated_at :desc]]})

(defn- re-order [issues search-mode]
  (if (= 1 search-mode)
    (let [top (sort-by #(:short_title %) 
                       (filter #(and (some? (:short_title %))
                                     (= 0 (:short_title_ints %))) issues))
          bottom (sort-by #(:short_title_ints %)
                          (filter #(> (:short_title_ints %) 0) issues))]
      (concat top bottom))
    (let [top (reverse (sort-by #(:short_title_ints %)
                                (filter #(> (:short_title_ints %) 0) issues)))
          bottom (reverse (sort-by #(:short_title %)
                                   (filter #(and (= (:short_title_ints %) 0)
                                                 (some? (:short_title %))) issues)))]
      (concat top bottom))))

(defn- filter-by-selected-secondary-contexts 
  [selected-secondary-contexts-set 
   secondary-contexts-unassigned-selected
   secondary-contexts-inverted
   link-issue?
   issues]
  (if (and (not link-issue?)
           (or secondary-contexts-unassigned-selected
               (seq selected-secondary-contexts-set)))
    ((if-not secondary-contexts-inverted filter remove)
     (fn [issue]
       (or 
        (and secondary-contexts-unassigned-selected
             (= 1 (count (:contexts issue))))
        
        (if-not secondary-contexts-inverted
          (and (not secondary-contexts-unassigned-selected)
               (every? identity (map #(contains? (set (keys (:contexts issue))) %) 
                                     selected-secondary-contexts-set)))
          (seq (set/intersection 
                (set (keys (:contexts issue)))
                selected-secondary-contexts-set)))))
     issues)
    issues))

(defn- get-events-view 
  [{{{{{:keys [events-view]} :current} :views} :data
     :as                                       selected-context} :selected-context
    global-events-view :events-view}]
  (or (if selected-context
        events-view
        global-events-view) 0))

(defn- do-fetch-ids 
  [db {:keys [q search-globally? selected-context]
       :or   {q ""}
       :as state
       }]
  (seq (fetch-ids db 
                  q 
                  (if search-globally? nil selected-context) 
                  (get-events-view state))))

(defn- filter-issues
  [{:keys [link-issue 
           selected-issue
           selected-context]} issues]
  (if-not link-issue 
    (remove #(= (:id selected-issue) (:id %)) issues)
    (if (= :issue link-issue)
      (let [issue-ids-to-exclude (conj (set (map :id (:related_issues selected-issue)))
                                       (:id selected-issue))]
        (remove #(issue-ids-to-exclude (:id %)) 
                issues))
      (remove #((set (keys (:contexts %))) (:id selected-context)) issues))))

(defn- sort-issues [{{{{{:keys [search-mode]} :current} :views} :data} 
                       :selected-context :as state} 
                    issues]
  (let [events-view (get-events-view state)
        in-events-view? (not= 0 events-view)]
    (->> issues
         (#(if in-events-view?
             (sort-by :date %) %))
         (#(if in-events-view?
             (filter :date %) %))
         (#(if (and in-events-view?
                    (= 2 events-view))
             (reverse %)
             %))
         (#(if (and (not in-events-view?)
                    (contains? #{1 2} search-mode))
             (re-order % search-mode)
             %)))))

(defn- search-issues'
  [db {:keys                                                                                                                                [link-issue]
       {{{{:keys [selected-secondary-contexts
                  secondary-contexts-inverted
                  secondary-contexts-unassigned-selected]} :current} :views} :data} :selected-context
       :as                                                                                                                                  opts}]
  (if-let [ids (do-fetch-ids db opts)]
    (->> ids
         (map #(:issues/id %))
         issues-query
         sql/format
         (jdbc/execute! db)
         (map common/post-process)
         (sort-issues opts)
         (filter-by-selected-secondary-contexts 
          (into #{} selected-secondary-contexts)
          secondary-contexts-unassigned-selected
          secondary-contexts-inverted
          (= :issue link-issue))
         (filter-issues opts))
    '()))

(defn- try-parse [item]
  (try (Integer/parseInt item)
       (catch Exception _e nil)))

(defn- pre-process-highlighted-secondary-contexts
  [highlighted-secondary-contexts]
  (->> highlighted-secondary-contexts
       (map try-parse)
       (remove nil?)))

(defn- calc-highlighted [db 
                         secondary-contexts
                         highlighted-secondary-contexts]
  (reduce (fn [acc val]
            (if (secondary-contexts val)
              (conj acc [val (conj (secondary-contexts val) true)])
              (if-let [title (:title (get-item/get-item db {:id val}))]
                (conj acc [val [title 0 true]])
                acc)))
          [] highlighted-secondary-contexts))

(defn- sort-secondary-contexts
  [db highlighted-secondary-contexts secondary-contexts]
  (let [highlighted-secondary-contexts (pre-process-highlighted-secondary-contexts
                                        highlighted-secondary-contexts)
        secondary-contexts             (into {} secondary-contexts)
        front                          (calc-highlighted db 
                                                         secondary-contexts 
                                                         highlighted-secondary-contexts)
        back                           (->> secondary-contexts
                                            (remove (fn [[k _v]]
                                                      (some #{k} highlighted-secondary-contexts)))
                                            (map (fn [[k [val title]]] [k [val title false]])))]
    (concat front (reverse (sort-by #(get-in % [1 1]) back)))))

(defn- get-aggregated-contexts 
  [db 
   opts 
   highlighted-secondary-contexts]
  (->> (search-issues' db (-> opts
                              (assoc :q "")
                              (dissoc :selected-issue)
                              (assoc :events-view 0)
                              (assoc-in [:selected-context :data :views :current :events-view] 0)
                              (assoc-in [:selected-context :data :views :current :search-mode] 0)
                              (assoc-in [:selected-context :data :views :current :selected-secondary-contexts] [])
                              (assoc-in [:selected-context :data :views :current :secondary-contexts-inverted] false)
                              (assoc-in [:selected-context :data :views :current :secondary-contexts-unassigned-selected] false)))
       (map :contexts)
       (map seq)
       (apply concat)
       (group-by first)
       (map #(do [(count (second %)) (first (second %))]))
       (sort-by first)
       reverse
       (map (fn [[count [id title]]] [id [title count]]))
       (sort-secondary-contexts db highlighted-secondary-contexts)))

(defn search-issues [db {{{:keys [highlighted-secondary-contexts]} :data  
                          :as selected-context} :selected-context
                         :as opts}]
  (try
    (let [opts (
                ;; TODO instead of doing this, make sure q is always at least ""
                if (:q opts) 
                 (update opts :q remove-some-chars)
                 ;; for destructuring in searcj-issues' to work properly when :q is present but has nil value
                 (dissoc opts :q))]
      (if-not selected-context
        [(search-issues' db opts) {}]
        [(search-issues' db opts) 
         (get-aggregated-contexts db 
                                  opts 
                                  highlighted-secondary-contexts)]))
    (catch Exception e
      (log/error (str "error in search-issues: " (.getMessage e) " - params were: " (with-out-str (pp/pprint opts))))
      (throw e))))

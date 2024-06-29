(ns datastore.search
  (:require [clojure.set :as set]
            [cambium.core :as log]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.issues.common :as common]
            [datastore.get-item :as get-item]))

(defmacro sectime
  [what expr]
  `(let [start# (. System (currentTimeMillis))
         ret# ~expr]
     (log/info (str "Elapsed time - " ~what ": " (/ (double (- (. System (currentTimeMillis)) start#)) 1000.0) " secs"))
     ret#))

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

(defn- query-string-contexts-query [q _selected-context]
  (sql/format (merge {:select [:issues.title
                               :issues.short_title
                               :issues.short_title_ints
                               :issues.id
                               :issues.data
                               :issues.is_context
                               :issues.updated_at_ctx]
                      :from  [:issues]
                      :where [:and
                              (when-not (= "" (or q ""))
                                [:raw (format "searchable @@ to_tsquery('simple', '%s')"
                                              (convert-q-to-query-string q))])
                              [:= :issues.is_context true]]
                      :order-by [[:updated_at_ctx :desc]]}
                     (when true #_(and (not selected-context)
                                (= "" (or q "")))
                       {:limit 100}))))

(defn- filter-contexts [{:keys [link-context selected-context selected-issue]} contexts]
  (if-not link-context
    (remove #(= (:id selected-context) (:id %)) contexts)
    (let [ids-of-contexts-to-remove (conj (set (map #(if (number? %)
                                                       %
                                                       (Integer/parseInt (if (keyword? %) 
                                                                           (name %) 
                                                                           %))) 
                                                    (keys (or (:contexts (:data selected-issue))
                                                              (:contexts (:data selected-context))))))
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
       (query-string-contexts-query q (:selected-context opts))
       (jdbc/execute! db)
       (map common/post-process-simple)
       (filter-contexts opts))
      (catch Exception e
        (log/error (str "error in search/search-contexts: " e " - param was: " q))
        (throw e)))))

(defn- fetch-issue-ids [ds q selected-context events-view link-issue search-mode selected-issue]
  (let [select [:issues.title
                :issues.short_title
                :issues.short_title_ints
                :issues.id
                :issues.data
                :issues.is_context
                :issues.updated_at
                :issues.date
                :issues.archived]
        urgent-events-query
        (sql/format
         {:select   select
          :from     [:issues]
          :order-by [[:updated_at :desc]]
          :where    [:and
                     [:<> :issues.date nil]
                     [:not= :issues.archived true]
                     [:< 
                      :date
                                        ;; we sort later
                      #_[:+ :events.date [:raw "interval '6 hours'"]] 
                      [:raw "NOW()"]]]})
        urgent-issues 
        (if (or link-issue 
                (not= 0 events-view)
                selected-context
                (and (string? q) (not= "" q)))
          '()
          (jdbc/execute! 
           ds 
           urgent-events-query))
        selected-context (when (:id selected-context) selected-context)
        search-clause       (if (not= "" q)
                              [:raw (format "searchable @@ to_tsquery('simple', '%s')" 
                                            (convert-q-to-query-string q))] 
                              [:=])
        join-clause         (if selected-context
                              [:collections [:= :issues.id :collections.item_id]]
                              [])
        join-where-clause   (if selected-context
                              (if (= :issue link-issue)
                                [:in :collections.container_id [:inline (keys (:contexts (:data selected-issue)))]]
                                [:= :collections.container_id (:id selected-context)])
                              [:=])
        exists-clause       (if (not= 0 events-view)
                              [:and
                               [:<> :issues.date nil]
                               [:not= :issues.archived [:inline (= 1 events-view)]]]
                              [:=])
        formatted-query (sql/format (merge
                                     {:select   select
                                      :from     [:issues]
                                      :order-by [[:issues.updated_at (if (= 1 search-mode)
                                                                       :asc 
                                                                       :desc)]]
                                      :join     join-clause
                                      :where    [:and [:and
                                                       exists-clause
                                                       join-where-clause
                                                       search-clause]
                                                 (when (seq urgent-issues)
                                                   [:not [:in :issues.id [:inline (map :issues/id urgent-issues)]]])]}
                                     (when (and (= "" q)
                                                (if selected-context
                                                  (= :issues link-issue)
                                                  (= 0 events-view)))
                                       {:limit 500})))
        issues (jdbc/execute! ds formatted-query)]
    (concat urgent-issues issues)))

(defn- re-order [search-mode issues]
  (if (= 2 search-mode)
    (let [_top (sort-by #(:short_title %) 
                       (filter #(and (some? (:short_title %))
                                     (= 0 (:short_title_ints %))) issues))
          bottom (sort-by #(:short_title_ints %)
                          (filter #(> (:short_title_ints %) 0) issues))]
      #_(concat top bottom)
      bottom)
    (let [top (reverse (sort-by #(:short_title_ints %)
                                (filter #(> (:short_title_ints %) 0) issues)))
          _bottom (reverse (sort-by #(:short_title %)
                                   (filter #(and (= (:short_title_ints %) 0)
                                                 (some? (:short_title %))) issues)))]
      #_(concat top bottom)
      top)))

(defn- expired-filter [issue]
  (and
   (not (:archived issue))
   (:date issue)
   (= 1 (.compareTo (java.time.Instant/now)
                    (java.time.Instant/parse (str (:date issue) "T05:45:00Z"))))))

;; TODO extract common pattern (top,bottom) with #re-order
(defn- pin-events [issues]
  (let [top (filter expired-filter issues)
        bottom (remove  expired-filter issues)]
    (concat top bottom)))

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
             (= 1 (count (:contexts (:data issue)))))
        
        (if-not secondary-contexts-inverted
          (and (not secondary-contexts-unassigned-selected)
               (every? identity (map #(contains? (set (keys (:contexts (:data issue)))) %) 
                                     selected-secondary-contexts-set)))
          (seq (set/intersection 
                (set (keys (:contexts (:data issue))))
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
  [db {:keys [q search-globally? selected-context link-issue selected-issue]
       :or   {q ""}
       :as state} search-mode]
  (seq (fetch-issue-ids db 
                        q 
                        (if search-globally? nil selected-context) 
                        (get-events-view state)
                        link-issue
                        search-mode
                        selected-issue)))

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
      (remove #(or ((set (keys (:contexts (:data %)))) (:id selected-context))
                   (= (:id %) (:id selected-context))) issues))))

(defn- sort-for-events-view 
  [issues events-view]
  (->> issues 
       (sort-by :date)
       (filter :date)
       (#(if (= 2 events-view)
           (reverse %)
           %))))

(defn- sort-for-regular-view 
  [issues 
   {{{{{:keys [search-mode]} :current} :views} :data} :selected-context
    :keys [link-issue]}]
  (cond->> issues
    (#{2 3} search-mode)
    (re-order search-mode)
    (and (not link-issue)
         (not (#{2 3} search-mode))) 
    pin-events))

(defn- sort-issues [state 
                    issues]
  (let [events-view (get-events-view state)
        in-events-view? (not= 0 events-view)]
    (if in-events-view? 
      (sort-for-events-view issues events-view)
      (sort-for-regular-view issues state))))

(defn- search-issues'
  [db {:keys                                                                                                                                [link-issue]
       {{{{:keys [selected-secondary-contexts
                  secondary-contexts-inverted
                  secondary-contexts-unassigned-selected
                  search-mode]} :current} :views} :data} :selected-context
       :as                                                                                                                                  opts}]
       (let [issues (do-fetch-ids db opts search-mode)]
         (->> issues
              (map common/post-process-simple)
              (sort-issues opts)
              (filter-by-selected-secondary-contexts 
               (into #{} selected-secondary-contexts)
               secondary-contexts-unassigned-selected
               secondary-contexts-inverted
               (= :issue link-issue))
              (filter-issues opts))))

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
  (let [issues (search-issues' db (-> opts
                                      (assoc :q "")
                                      (dissoc :selected-issue)
                                      (assoc :events-view 0)
                                      (assoc-in [:selected-context :data :views :current :events-view] 0)
                                      (assoc-in [:selected-context :data :views :current :search-mode] 0)
                                      (assoc-in [:selected-context :data :views :current :selected-secondary-contexts] [])
                                      (assoc-in [:selected-context :data :views :current :secondary-contexts-inverted] false)
                                      (assoc-in [:selected-context :data :views :current :secondary-contexts-unassigned-selected] false)))]
    (sectime
     "get-aggregated-contexts#after-search-issues'"
     (->> issues
          (map #(get-in % [:data :contexts]))
          (map seq)
          (apply concat)
          (group-by first)
          (map #(do [(count (second %)) (first (second %))]))
          (sort-by first)
          reverse
          (map (fn [[count [id title]]] [id [title count]]))

          (sort-secondary-contexts db highlighted-secondary-contexts)))))

(defn fetch-aggregated-contexts [db {{{:keys [highlighted-secondary-contexts]} :data} :selected-context
                                     :as opts}]
  (sectime
   "fetch-aggregated-contexts"
   (try
     (let [opts (
                ;; TODO instead of doing this, make sure q is always at least ""
                 if (:q opts) 
                  (update opts :q remove-some-chars)
                 ;; for destructuring in searcj-issues' to work properly when :q is present but has nil value
                  (dissoc opts :q))]
       (sectime "get-aggregated-contexts"
                    (get-aggregated-contexts db 
                                             opts 
                                             highlighted-secondary-contexts)))
     (catch Exception e
       (log/error (str "error in fetch-aggregated-contexts: " (.getMessage e) " - params were: " (with-out-str (pp/pprint opts))))
       (throw e)))))

(defn search-issues [db {:keys [skip-context-aggregation?
                                only-context-aggregation?]
                         :as opts}]
  (log/info (str "search-issues:" skip-context-aggregation? ":" only-context-aggregation?))
  (sectime
   "search-issues"
   (try
     (let [opts (
                ;; TODO instead of doing this, make sure q is always at least ""
                 if (:q opts) 
                  (update opts :q remove-some-chars)
                 ;; for destructuring in searcj-issues' to work properly when :q is present but has nil value
                  (dissoc opts :q))]
       [(sectime "search-issues/issues" 
                 (search-issues' db opts)) {}])
     (catch Exception e
       (log/error (str "error in search-issues: " (.getMessage e) " - params were: " (with-out-str (pp/pprint opts))))
       (throw e)))))

(ns datastore.search
  (:require [clojure.set :as set]
            [cambium.core :as log]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.issues.common :as common]
            [datastore.contexts :as contexts]
            [datastore.contexts.core :as contexts.core]))

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

(def all-contexts-query (sql/format {:select :*
                                     :from [:contexts]
                                     :order-by [[:important :desc] [:updated_at :desc]]}))

(defn- query-string-contexts-query [q]
  (sql/format {:select :*
               :from   [:contexts]
               :where [:raw (format "searchable @@ to_tsquery('simple', '%s')"
                                    (convert-q-to-query-string q))]
               :order-by [[:important :desc] [:updated_at :desc]]}))

(defn- filter-contexts [{:keys [link-context selected-context selected-issue]} contexts]
  (if-not link-context
    (remove #(= (:id selected-context) (:id %)) contexts)
    (let [ids-of-contexts-to-remove (set (keys (:contexts selected-issue)))]
      (remove #(ids-of-contexts-to-remove (:id %)) contexts))))

(defn search-contexts
  [ds opts]
  (let [opts (if (string? opts) 
               {:q opts}
               opts)
        {:keys [q]} opts]
    (try
      (->>
       (if (= "" (or q ""))
         (jdbc/execute! ds all-contexts-query)
         (jdbc/execute! ds (query-string-contexts-query q)))
       (map contexts.core/post-process)
       (filter-contexts opts))
      (catch Exception e
        (log/error (str "error in search-contexts: " (.getMessage e) " - param was: " q))
        (throw e)))))

(defn- fetch-ids [ds q selected-context show-events?]
  (let [selected-context (when (:id selected-context) selected-context)
        search-clause       (if (not= "" q)
                              [:raw (format "searchable @@ to_tsquery('simple', '%s')" 
                                            (convert-q-to-query-string q))] 
                              [:=])
        join-clause         (if selected-context
                              [:context_issue [:= :issues.id :context_issue.issue_id]]
                              [])
        join-where-clause   (if selected-context
                              [:= :context_issue.context_id (:id selected-context)]
                              [:=])
        exists-clause       (if show-events? 
                              [:exists {:select [:events.id]
                                        :from   [:events]
                                        :where  [:and
                                                 [:= :events.issue_id :issues.id]
                                                 [:not= :events.archived [:inline true]]]}]
                              [:=])
        formatted-query (sql/format (merge
                                     {:select   [:issues.id]
                                      :from     [:issues]
                                      :order-by [[:important :desc] 
                                                 [:updated_at :desc]]
                                      :join     join-clause
                                      :where    [:or
                                                 [:and
                                                  exists-clause
                                                  join-where-clause
                                                  search-clause]
                                                 (if (and (= "" q)
                                                          (not selected-context)
                                                          (not show-events?))
                                                   [:= :important [:inline true]]
                                                   nil)]}
                                     (when (and (= "" q)
                                                (not selected-context)
                                                (not show-events?))
                                       {:limit 500})))]
    (jdbc/execute! ds formatted-query)))

(defn- issues-query [ids]
  {:select   [:issues.*
              {:select :date
               :from   [:events]
               :where  [:= :events.issue_id :issues.id]}
              [[:array_agg :contexts.id] :context_ids]
              [[:array_agg :contexts.title] :context_titles]]
   :from     [:issues]
   :join     [:context_issue [:= :issues.id :context_issue.issue_id]
              :contexts [:= :context_issue.context_id :contexts.id]]
   :where    [:in :issues.id [:inline ids]]
   :group-by [:issues.id]
   :order-by [[:issues.important :desc] [:issues.updated_at :desc]]})

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
   issues]
  (if (or secondary-contexts-unassigned-selected
          (seq selected-secondary-contexts-set))
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

(defn- do-fetch-ids 
  [db {:keys [q
              selected-context
              show-events?
              search-globally?]
       :or   {q ""}}]
  (seq (fetch-ids db q (if search-globally? nil selected-context) show-events?)))

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

(defn- search-issues'
  [db {:keys [show-events?]
       {{{{:keys [selected-secondary-contexts
                  secondary-contexts-inverted
                  secondary-contexts-unassigned-selected
                  search-mode]} :current} :views} :data} :selected-context
       :as opts}]
  (if-let [ids (do-fetch-ids db opts)]
    (->> ids
         (map #(:issues/id %))
         issues-query
         sql/format
         (jdbc/execute! db)
         (map common/post-process)
         (#(if show-events? (sort-by :date %) %))
         (#(if (contains? #{1 2} search-mode)
             (re-order % search-mode)
             %))
         (filter-by-selected-secondary-contexts 
          (into #{} selected-secondary-contexts)
          secondary-contexts-unassigned-selected
          secondary-contexts-inverted)
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
              (if-let [title (:title (contexts/get-context db {:id val}))]
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

(defn search-issues [db {:keys [show-events?]
                         {{:keys [highlighted-secondary-contexts]} :data  
                          :as selected-context} :selected-context
                         :as opts}]
  (try
    (let [opts (
                ;; TODO instead of doing this, make sure q is always at least ""
                if (:q opts) 
                 (update opts :q remove-some-chars)
                 ;; for destructuring in searcj-issues' to work properly when :q is present but has nil value
                 (dissoc opts :q))]
      (if-not (or selected-context show-events?)
        [(search-issues' db opts) {}]
        [(search-issues' db opts) 
         (get-aggregated-contexts db 
                                  opts 
                                  highlighted-secondary-contexts)]))
    (catch Exception e
      (log/error (str "error in search-issues: " (.getMessage e) " - params were: " (with-out-str (pp/pprint opts))))
      (throw e))))

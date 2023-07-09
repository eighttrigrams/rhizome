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

(defn search-contexts
  [ds q]
  (try
    (->>
     (if (= "" (or q ""))
       (jdbc/execute! ds
                      (sql/format {:select :*
                                   :from [:contexts]
                                   :order-by [[:important :desc] [:updated_at :desc]]}))
       (jdbc/execute! ds
                      (sql/format {:select :*
                                   :from   [:contexts]
                                   :where [:raw (format "searchable @@ to_tsquery('simple', '%s')" 
                                                        (convert-q-to-query-string q))]
                                   :order-by [[:important :desc] [:updated_at :desc]]})))
     (map contexts.core/post-process))
    (catch Exception e
      (log/error (str "error in search-contexts: " (.getMessage e) " - param was: " q))
      (throw e))))

(defn- fetch-ids [ds q selected-context show-events?]
  (let [search-clause       (if (not= "" q)
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
                              [:=])]
    (jdbc/execute!
     ds
     (sql/format
      (merge
       {:select   [:issues.id]
        :from     [:issues]
        :order-by [[:important :desc] [:updated_at :desc]]
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
         {:limit 500}))))))

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

(defn- filter-by-selected-secondary-contexts [selected-secondary-contexts-set 
                                              unassigned-secondary-contexts-selected?
                                              secondary-contexts-inverted?
                                              secondary-contexts-and?
                                              issues]
  (if (or unassigned-secondary-contexts-selected?
          (seq selected-secondary-contexts-set))
    ((if-not secondary-contexts-inverted? filter remove)
     (fn [issue]
       (or 
        (and unassigned-secondary-contexts-selected?
             (= 1 (count (:contexts issue))))
        
        (if secondary-contexts-and?
          (every? identity (map #(contains? (set (keys (:contexts issue))) %) 
                                selected-secondary-contexts-set))
          (seq (set/intersection 
                (set (keys (:contexts issue)))
                selected-secondary-contexts-set))))
       )issues)
    issues))

(defn- do-fetch-ids 
  [db {:keys [q
              selected-context
              show-events?
              search-globally?]
       :or   {q ""}}]
  (seq (fetch-ids db q (if search-globally? nil selected-context) show-events?)))

(defn- search-issues'
  [db {:keys [show-events?
              unassigned-secondary-contexts-selected?
              secondary-contexts-and?
              secondary-contexts-inverted?]
       {{:keys [selected-secondary-contexts]} :data
        :as selected-context} :selected-context
       :as opts}]
  (if-let [ids (do-fetch-ids db opts)]
    (->> ids
         (map #(:issues/id %))
         issues-query
         sql/format
         (jdbc/execute! db)
         (map common/post-process)
         (#(if show-events? (sort-by :date %) %))
         (#(if (contains? #{1 2} (:search_mode selected-context))
             (re-order % (:search_mode selected-context))
             %))
         (filter-by-selected-secondary-contexts (into #{} selected-secondary-contexts)
                                                unassigned-secondary-contexts-selected?
                                                secondary-contexts-inverted?
                                                secondary-contexts-and?))
    '()))

(defn search-issues [db {:keys [show-events?]
                         {{:keys [selected-secondary-contexts]} :data  
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
        (let [aggregated-contexts
              (->> (search-issues' db (-> opts 
                                          (assoc-in [:selected-context :data :selected-secondary-contexts] [])
                                          (dissoc
                                           :show-events?
                                           :unassigned-secondary-contexts-selected?
                                           :secondary-contexts-inverted?)))
                   (map :contexts)
                   (map seq)
                   (apply concat)
                   (group-by first)
                   (map #(do [(count (second %)) (first (second %))]))
                   (sort-by first)
                   (map second)
                   reverse)
              aggregated-contexts (reduce (fn [acc val]
                                            (conj acc [val (:title (contexts/get-context db {:id val}))])) 
                                          aggregated-contexts (set/difference 
                                                               (into #{} selected-secondary-contexts)
                                                               (set (map first aggregated-contexts))))]
          [(search-issues' db opts) aggregated-contexts])))
    (catch Exception e
      (log/error (str "error in search-issues: " (.getMessage e) " - params were: " (with-out-str (pp/pprint opts))))
      (throw e))))

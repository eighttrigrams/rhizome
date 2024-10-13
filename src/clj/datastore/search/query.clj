(ns datastore.search.query
  (:require [cambium.core :as log]
            [clojure.string :as str]
            [honey.sql :as sql]))

(def select [:issues.title
             :issues.short_title
             :issues.short_title_ints
             :issues.id
             :issues.data
             :issues.is_context
             :issues.updated_at
             :issues.date
             :issues.archived])

(defn remove-some-chars [q]
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

(defn convert-q-to-query-string [q]
  (let [qs
        (str/join " & " (map #(str % ":*") (str/split (remove-some-chars q) #" ")))]
    (if (= ":*" qs)
      "*"
      qs)))

(defn- wrap-order-and-limit [formatted-query selected-context link-issue]
  (let [formatted-query (if (and selected-context link-issue) 
                          (let [[q :as original-query] formatted-query
                                formatted-query        (str "SELECT * FROM (" q ") AS issues ORDER BY issues.updated_at DESC LIMIT 500")]
                            (assoc original-query 0 formatted-query))
                          formatted-query)]
    (log/info (str "formatted-query: " formatted-query))
    formatted-query))

(defn- get-search-clause [q]
  (when (not= "" q)
    [:raw (format "searchable @@ to_tsquery('simple', '%s')" 
                  (convert-q-to-query-string q))]))

(defn- get-events-exist-clause [events-view]
  (when (not= 0 events-view)
    [:and
     [:<> :issues.date nil]
     [:not= :issues.archived [:inline (= 1 events-view)]]]))

(defn do-fetch-ids 
  [{:keys [q link-issue]
    :or   {q ""}} 
   selected-context
   search-mode
   events-view
   issue-ids-to-remove
   join-ids
   and-query?]
  (-> 
   (sql/format 
    (merge
     {:select select
      :from   [:issues]
      :where  [:and [:and
                     (get-events-exist-clause events-view)
                     (when join-ids [:in :collections.container_id [:inline join-ids]])
                     (get-search-clause q)]
               (when issue-ids-to-remove
                 [:not [:in :issues.id [:inline issue-ids-to-remove]]])]}
     (when join-ids
       {:group-by [:issues.id]
        :join     [:collections [:= :issues.id :collections.item_id]]})
     (when and-query?
       {:having [:raw (str "COUNT(issues.id) = " (count join-ids))]})
     (when-not (and selected-context link-issue)
       {:order-by [[:issues.updated_at (if (= 1 search-mode)
                                         :asc 
                                         :desc)]]})
     (when (and (= "" q)
                (not selected-context)
                (= 0 events-view))
       {:limit 500})))
    ;; TODO i could do the sorting and limiting uniformly here
   (wrap-order-and-limit selected-context link-issue)))

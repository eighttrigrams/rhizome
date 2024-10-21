(ns datastore.items
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [cambium.core :as log]
            [cheshire.core :as json]
            [datastore.issues.common :as common]
            datastore.relations))

(declare get-item)

(defn- basic-issues-query [id]
  {:select   [:issues.*]
   :from     [:issues]
   :where    [:= :issues.id [:inline id]]
   :group-by [:issues.id]
   :order-by [[:issues.updated_at :desc]]})

(defn- get-issue-without-related-issues [db id]
  (-> (basic-issues-query id)
      sql/format
      (#(jdbc/execute-one! db % {:return-keys true}))))

(defn get-item
  [db {:keys [id]}]
  (try
    (-> (get-issue-without-related-issues db id)
        common/post-process-simple)
    (catch java.lang.Exception e
      (prn "get-issue-----" (.getMessage e))
      (throw e))))

(defn- basic-title-query [title]
  {:select   [:issues.*]
   :from     [:issues]
   :where    [:= :issues.title [:inline title]]
   :group-by [:issues.id] ;; TODO remove
   :order-by [[:issues.updated_at :desc]]})

(defn- get-issue-without-related-issues-by-title [db id]
  (-> (basic-title-query id)
      sql/format
      (#(jdbc/execute-one! db % {:return-keys true}))))

(defn get-item-by-title 
  [db {:keys [title]}]
  (try
    (-> (get-issue-without-related-issues-by-title db title)
        common/post-process-simple
        (assoc :contexts {})
        (assoc :related_issues {}))
    (catch java.lang.Exception e
      (prn "get-issue-----" (.getMessage e))
      (throw e))))

(defn- basic-find-query [path match]
  {:select   [:issues.*]
   :from     [:issues]
   :where    [:= path [:inline match]]})

(defn- get-issue-without-related-issues-by-path [db path url]
  (-> (basic-find-query [:raw path] url)
      sql/format
      (#(jdbc/execute-one! db % {:return-keys true}))))

(defn get-item-by-path
  [db path url]
  (try
    (-> (get-issue-without-related-issues-by-path db path url)
        common/post-process-simple
        (assoc :contexts {}))
    (catch java.lang.Exception e
      (prn "get-issue-----" (.getMessage e))
      (throw e))))

(defn get-items-by-path [db path url]
  (-> (basic-find-query [:raw path] url)
      sql/format
      (#(jdbc/execute! db % {:return-keys true}))))

(defn update-item [db
                   {:keys [id title short_title tags data date archived] :as item}
                   mode]
  (common/delete-date db id)
  (when date
    (common/insert-date db id date archived))
  (let [old-item (get-item db item)
        old-data (:data old-item)
        set (merge {:title       [:inline title]
                    :short_title [:inline short_title]
                    :tags        [:inline tags]}
                   (merge {:data       [:inline (json/generate-string
                                                 (if data
                                                   (merge old-data data)
                                                   {}))]})
                   (if (= :context mode)
                     {:updated_at_ctx [:raw "NOW()"]}
                     {:updated_at [:raw "NOW()"]}))
        formatted-sql (sql/format {:update [:issues]
                                   :where  [:= :id [:inline id]]
                                   :set    set})
        result (jdbc/execute-one! db
                                  formatted-sql
                                  {:return-keys true})]
    (when (and (= :context mode)
               (or (not= (:title old-item) title)
                   (not= (:short_title old-item) short_title)))
      (future
        (try
          (datastore.relations/update-collection-title-in-collection-items-for-children db id title short_title)
          (catch Exception e
            (log/error (.getMessage e))))))
    result))

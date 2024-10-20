(ns datastore.items
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [cambium.core :as log]
            [cheshire.core :as json]
            [datastore.issues.common :as common]))

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
  "Gets an issue, including related issues.
   
   {:id 123
    :title \"some-title-1\"
    :contexts {223 \"some-context-title-1\"}
    :related_issues '({:id 124
                       :title \"some-title-2\"
                       :contexts {224 \"some-context-title\"}})
   }
   "
  [db {:keys [id]}]
  (try
    (let [relations nil]
      (-> (get-issue-without-related-issues db id)
          common/post-process-simple
          (assoc :related_issues (or relations #{}))))
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

(defn set-collection-titles-of-new-issue [db item-id]
  (let [data (:issues/data (jdbc/execute-one! db
                                              (sql/format {:select [:data]
                                                           :from   [:issues]
                                                           :where  [:= :id [:inline item-id]]})
                                              {:return-keys true}))
        data (cond (nil? data) {}
                   :else (json/parse-string (.getValue data)))
        data (if (get data "contexts")
               data
               (assoc data "contexts" {}))
        contexts (dissoc (into {}
                               (map (fn [{:issues/keys [id title short_title]}]
                                      [id {:title (if (seq short_title)
                                            short_title
                                            title)
                                           :show-badge? true}]
                                      ) (jdbc/execute! db
                                                      (sql/format {:select [:issues.id :title :short_title]
                                                                   :from   [:collections]
                                                                   :join   [:issues [:= :collections.container_id :issues.id]]
                                                                   :where  [:= :collections.item_id [:inline item-id]]})
                                                      {:return-keys true})))
                         item-id)]
    (jdbc/execute-one! db
                       (sql/format {:update [:issues]
                                    :where  [:= :id [:inline item-id]]
                                    :set    {:data [:inline (json/generate-string (assoc data "contexts" contexts))]}})
                       {:return-keys true})))

(defn update-collection-title-in-collection-items
  "Standard use case is that you know item-id references id via contexts. That id has a new title, so we update it.
   @param constraints a list of ids; when set, the contexts of the item with item-id will be reduced to the ones present in that list
     so the use case is not to set the title in an item's context (with a given id), but to remove contexts"
  ([db item-id id short_title title] 
   (update-collection-title-in-collection-items db item-id id short_title title nil))
  ([db item-id id short_title title constraints]
   (let [data (:issues/data (jdbc/execute-one! db
                                               (sql/format {:select [:data]
                                                            :from   [:issues]
                                                            :where  [:= :id [:inline item-id]]})
                                               {:return-keys true}))
         data (cond (nil? data) {}
                    :else (json/parse-string (.getValue data)))
         data (if (get data "contexts")
                data
                (assoc data "contexts" {}))
         data (update data "contexts" (fn [contexts]
                                        (cond
                                          (true? constraints)
                                          (dissoc contexts (str id))
                                          (seq constraints)
                                          (select-keys contexts (map str constraints))
                                          :else
                                          (if (map? (get contexts (str id)))
                                            (assoc-in contexts [(str id) "title"]  
                                                      (if (seq short_title)
                                                        short_title
                                                        title))
                                            (assoc contexts (str id)   
                                                   {:show-badge? true
                                                    :title       (if (seq short_title)
                                                                   short_title
                                                                   title)})))))]
     (jdbc/execute-one! db
                        (sql/format {:update [:issues]
                                     :where  [:= :id [:inline item-id]]
                                     :set    {:data [:inline (json/generate-string data)]}})
                        {:return-keys true}))))

(defn- update-collection-title-in-collection-items-for-children 
  [db id title short_title]
  (let [item-ids (doall (map :collections/item_id
                             (jdbc/execute! db
                                            (sql/format {:select [:item_id]
                                                         :from   [:collections]
                                                         :where  [:= :container_id [:inline id]]})
                                            {:return-keys true})))]
    (doall (for [item-id item-ids]
             (update-collection-title-in-collection-items db item-id id short_title title)))))

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
          (update-collection-title-in-collection-items-for-children db id title short_title)
          (catch Exception e
            (log/error (.getMessage e))))))
    result))

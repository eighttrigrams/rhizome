(ns rest-api
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [cheshire.core :as json]
            [cambium.core :as log]
            [et.vp.ds :as datastore]
            [et.vp.ds.helpers :refer [post-process-base]]
            [repository.insertion :as insertion]))

(defn- json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status status :headers {"Content-Type" "application/json"} :body (json/generate-string body)}))

(defn- parse-json-body
  [req]
  (try (some-> req
               :body
               slurp
               (json/parse-string true))
       (catch Exception _ nil)))

(defn- item->api
  [{:keys [id title short_title description is_context data inserted_at updated_at date
           annotation]}]
  (cond-> {:id id
           :title title
           :short-title short_title
           :is-context (boolean is_context)
           :inserted-at inserted_at
           :updated-at updated_at}
    description (assoc :description description)
    date (assoc :date date)
    annotation (assoc :annotation annotation)
    (-> data
        :contexts)
      (assoc :contexts
        (into {}
              (map (fn [[k v]] [(str k) (if (map? v) (:title v) v)])
                (-> data
                    :contexts))))))

(defn list-contexts
  [db]
  (let [rows (jdbc/execute! db
                            (sql/format {:select [:id :title :short_title :is_context :inserted_at
                                                  :updated_at]
                                         :from [:items]
                                         :where [:= :is_context true]
                                         :order-by [[:updated_at_ctx :desc]]
                                         :limit 200}))]
    (json-response (map (fn [row]
                          (let [r (post-process-base row)]
                            {:id (:id r) :title (:title r) :short-title (:short_title r)}))
                     rows))))

(defn search-contexts
  [db q]
  (let [pattern (str "%" q "%")
        rows (jdbc/execute! db
                            (sql/format {:select [:id :title :short_title]
                                         :from [:items]
                                         :where [:and [:= :is_context true]
                                                 [:or [:like :title [:inline pattern]]
                                                  [:like :short_title [:inline pattern]]]]
                                         :order-by [[:updated_at_ctx :desc]]
                                         :limit 50}))]
    (json-response (map (fn [row]
                          (let [r (post-process-base row)]
                            {:id (:id r) :title (:title r) :short-title (:short_title r)}))
                     rows))))

(defn get-item
  [db id]
  (try (let [item (datastore/get-item db {:id (Integer/parseInt id)})]
         (if item (json-response (item->api item)) (json-response 404 {:error "Item not found"})))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))))

(defn create-item
  [db req]
  (let [body (parse-json-body req)]
    (if-not (:title body)
      (json-response 400 {:error "title is required"})
      (let [context-ids (set (or (:context-ids body) []))]
        (if (empty? context-ids)
          (json-response 400 {:error "context-ids is required (at least one context)"})
          (try (let [primary-id (first context-ids)
                     rest-ids (disj context-ids primary-id)
                     item (insertion/insert-item db (:title body) {:id primary-id} rest-ids)
                     item (if (and (map? item) (:sort-idx body))
                            (do (jdbc/execute! db
                                               (sql/format {:update [:items]
                                                            :set {:sort_idx [:inline (:sort-idx body)]}
                                                            :where [:= :id [:inline (:id item)]]}))
                                (datastore/get-item db {:id (:id item)}))
                            item)
                     item (if (and (map? item) (:description body))
                            (datastore/update-context-description
                              db
                              {:id (:id item) :description (:description body)})
                            item)]
                 (log/info (str "REST INSERT item id=" (:id item)
                                " title=\"" (:title body) "\""
                                " contexts=" context-ids
                                (when (:sort-idx body) (str " sort-idx=" (:sort-idx body)))))
                 (if (map? item)
                   (json-response 201 (item->api item))
                   (json-response 201 {:created true})))
               (catch Exception e
                 (log/error e "REST API: create-item failed")
                 (json-response 500 {:error (.getMessage e)}))))))))

(defn find-by-sort-idx
  [db sort-idx context-ids-str]
  (try (let [sort-idx (Integer/parseInt sort-idx)
             context-ids (mapv #(Integer/parseInt (clojure.string/trim %))
                               (clojure.string/split context-ids-str #","))
             base-query {:select [:items.id :items.title :items.sort_idx]
                         :from [:items]
                         :where [:and
                                 [:= :items.sort_idx [:inline sort-idx]]
                                 (into [:and]
                                       (map (fn [cid]
                                              [:in :items.id
                                               {:select [:target_id]
                                                :from [:relations]
                                                :where [:= :owner_id [:inline cid]]}])
                                            context-ids))]
                         :limit 1}
             rows (jdbc/execute! db (sql/format base-query))]
         (if (seq rows)
           (let [r (first rows)]
             (json-response {:id (:items/id r) :title (:items/title r) :sort-idx (:items/sort_idx r)}))
           (json-response 404 {:error "Not found"})))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid parameters"}))))

(defn update-item-description
  [db id req]
  (try (let [id (Integer/parseInt id)
             body (parse-json-body req)]
         (if-not (:description body)
           (json-response 400 {:error "description is required"})
           (let [item (datastore/get-item db {:id id})]
             (if-not item
               (json-response 404 {:error "Item not found"})
               (do (log/info (str "REST UPDATE item id=" id
                                  " title=\"" (:title item) "\""
                                  " description-length=" (count (:description body))))
                   (let [updated (datastore/update-context-description db {:id id :description (:description body)})]
                     (json-response (item->api updated))))))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))
       (catch Exception e
         (log/error e "REST API: update-item-description failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn create-context
  [db req]
  (let [body (parse-json-body req)]
    (if-not (:title body)
      (json-response 400 {:error "title is required"})
      (try (let [ctx (datastore/new-context db {:title (:title body)})]
             (json-response 201 {:id (:id ctx) :title (:title ctx)}))
           (catch Exception e
             (log/error e "REST API: create-context failed")
             (json-response 500 {:error (.getMessage e)}))))))

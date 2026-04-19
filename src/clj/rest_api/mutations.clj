(ns rest-api.mutations
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [cambium.core :as log]
            [et.vp.ds :as datastore]
            [rest-api.middleware :as mw]
            [repository.insertion :as insertion]
            [rest-api.util :refer [json-response parse-json-body item->api]]))

(defn create-item
  "POST /rest/items — create a new item. JSON body: {\"title\" (required),
  \"context-ids\" (required, at least one), \"description\" (optional),
  \"sort-idx\" (optional int, e.g. a page number). Gated by recording mode: when
  off, the write is logged and dropped with 201 {:created true} stub."
  [db req]
  (let [body (parse-json-body req)]
    (cond
      (not (:title body))
        (json-response 400 {:error "title is required"})
      (empty? (set (or (:context-ids body) [])))
        (json-response 400 {:error "context-ids is required (at least one context)"})
      :else
        (mw/log-and-guard
          "create-item"
          {:title (:title body)
           :context-ids (:context-ids body)
           :sort-idx (:sort-idx body)
           :description-length (count (or (:description body) ""))}
          (json-response 201 {:created true})
          (fn []
            (try (let [context-ids (set (:context-ids body))
                       primary-id (first context-ids)
                       rest-ids (disj context-ids primary-id)
                       item (insertion/insert-item db (:title body) {:id primary-id} rest-ids)
                       item (if (and (map? item) (:sort-idx body))
                              (do (jdbc/execute! db
                                                 (sql/format {:update [:items]
                                                              :set {:sort_idx [:inline
                                                                               (:sort-idx body)]}
                                                              :where [:= :id
                                                                      [:inline (:id item)]]}))
                                  (datastore/get-item db {:id (:id item)}))
                              item)
                       item (if (and (map? item) (:description body))
                              (datastore/update-context-description
                                db
                                {:id (:id item) :description (:description body)})
                              item)]
                   (if (map? item)
                     (json-response 201 (item->api item))
                     (json-response 201 {:created true})))
                 (catch Exception e
                   (log/error e "REST API: create-item failed")
                   (json-response 500 {:error (.getMessage e)}))))))))

(defn update-item-description
  "PUT /rest/items/:id — replace an item's description. JSON body: {\"description\"}.
  Gated by recording mode. 404 if the item does not exist."
  [db id req]
  (try (let [id (Integer/parseInt id)
             body (parse-json-body req)]
         (cond
           (not (:description body))
             (json-response 400 {:error "description is required"})
           :else
             (let [item (datastore/get-item db {:id id})]
               (if-not item
                 (json-response 404 {:error "Item not found"})
                 (mw/log-and-guard
                   "update-item-description"
                   {:id id
                    :title (:title item)
                    :description-length (count (:description body))}
                   (json-response (item->api (assoc item :description (:description body))))
                   (fn []
                     (try (let [updated (datastore/update-context-description
                                          db
                                          {:id id :description (:description body)})]
                            (json-response (item->api updated)))
                          (catch Exception e
                            (log/error e "REST API: update-item-description failed")
                            (json-response 500 {:error (.getMessage e)})))))))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))))

(defn create-context
  "POST /rest/contexts — create a new context (item with is_context=true).
  JSON body: {\"title\"}. Gated by recording mode."
  [db req]
  (let [body (parse-json-body req)]
    (if-not (:title body)
      (json-response 400 {:error "title is required"})
      (mw/log-and-guard
        "create-context"
        {:title (:title body)}
        (json-response 201 {:id nil :title (:title body)})
        (fn []
          (try (let [ctx (datastore/new-context db {:title (:title body)})]
                 (json-response 201 {:id (:id ctx) :title (:title ctx)}))
               (catch Exception e
                 (log/error e "REST API: create-context failed")
                 (json-response 500 {:error (.getMessage e)}))))))))

(defn toggle-recording-mode
  "POST /rest/recording-mode/toggle — toggle the write-gate. While ON, mutating
  endpoints execute; while OFF they log intent and return 403 {:dropped true}.
  Prefer the in-app shortcut Option+Shift+W for day-to-day toggling."
  []
  (let [now (mw/toggle!)]
    (log/info {:recording now} (str "RECORDING MODE " (if now "ON" "OFF")))
    (json-response {:recording now})))

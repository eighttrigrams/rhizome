(ns rest-api.mutations
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [cambium.core :as log]
            [cheshire.core :as json]
            [et.vp.ds :as datastore]
            [et.vp.ds.relations :as relations]
            [et.vp.ds.search :as search]
            [rest-api.middleware :as mw]
            [repository.insertion :as insertion]
            [repository.deletion]
            [semsearch.backfill :as backfill]
            [rest-api.util :refer [json-response parse-json-body item->api]]))

(defn- embed-item-best-effort!
  "Embed title + description for an item and store in items.embedding. Logs and
  swallows errors — the caller's write succeeds even if Ollama is unreachable."
  [db {:keys [id] :as item}]
  (when id
    (try (backfill/embed-and-store! db item)
         (catch Exception e
           (log/error e (str "embed-item-best-effort! failed for id " id))))))

(defn- patch-item!
  "Run an UPDATE on items SET <set-map> WHERE id=<id> and re-fetch."
  [db id set-map]
  (jdbc/execute! db
                 (sql/format {:update [:items]
                              :set set-map
                              :where [:= :id [:inline id]]}))
  (datastore/get-item db {:id id}))

(defn- apply-sort-idx
  [db item sort-idx]
  (if (and (map? item) sort-idx)
    (patch-item! db (:id item) {:sort_idx [:inline sort-idx]})
    item))

(defn- apply-description
  [db item description]
  (if (and (map? item) description)
    (datastore/update-context-description db {:id (:id item) :description description})
    item))

(defn- create-item-impl
  [db {:keys [title context-ids sort-idx description] :as _body}]
  (try (let [context-ids (set context-ids)
             primary-id (first context-ids)
             rest-ids (disj context-ids primary-id)
             item (insertion/insert-item db title {:id primary-id} rest-ids)
             item (apply-sort-idx db item sort-idx)
             item (apply-description db item description)]
         (when (map? item)
           (embed-item-best-effort! db item))
         (if (map? item)
           (json-response 201 (item->api item))
           (json-response 201 {:created true})))
       (catch Exception e
         (log/error e "REST API: create-item failed")
         (json-response 500 {:error (.getMessage e)}))))

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
          (fn [] (create-item-impl db body))))))

(defn- update-item-description-impl
  [db id description]
  (try (let [updated (datastore/update-context-description
                       db
                       {:id id :description description})]
         (embed-item-best-effort! db updated)
         (json-response (item->api updated)))
       (catch Exception e
         (log/error e "REST API: update-item-description failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn update-item-description
  "PUT /rest/items/:id — replace an item's description. JSON body: {\"description\"}.
  Gated by recording mode. 404 if the item does not exist."
  [db id req]
  (try (let [id (Integer/parseInt id)
             {:keys [description]} (parse-json-body req)]
         (cond
           (not description)
             (json-response 400 {:error "description is required"})
           :else
             (let [item (datastore/get-item db {:id id})]
               (if-not item
                 (json-response 404 {:error "Item not found"})
                 (mw/log-and-guard
                   "update-item-description"
                   {:id id :title (:title item) :description-length (count description)}
                   (json-response (item->api (assoc item :description description)))
                   (fn [] (update-item-description-impl db id description)))))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))))

(defn- context-extras-set
  [{:keys [short-title sort-idx hide-in-global-search]}]
  (cond-> {}
    short-title
      (assoc :short_title [:inline short-title])
    sort-idx
      (assoc :sort_idx [:inline sort-idx])
    (true? hide-in-global-search)
      (assoc :hide_in_global_search [:inline true])))

(defn- create-context-impl
  [db {:keys [title] :as body}]
  (try (let [ctx (datastore/new-context db {:title title})
             context-extras-set (context-extras-set body)
             ctx (if (seq context-extras-set) (patch-item! db (:id ctx) context-extras-set) ctx)]
         (json-response 201 (item->api ctx)))
       (catch Exception e
         (log/error e "REST API: create-context failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn create-context
  "POST /rest/contexts — create a new context (item with is_context=true).
  JSON body: {\"title\" (required), \"short-title\" (optional),
  \"sort-idx\" (optional int), \"hide-in-global-search\" (optional bool — when
  true, the context is excluded from global contexts search). Gated by
  recording mode."
  [db req]
  (let [body (parse-json-body req)]
    (if-not (:title body)
      (json-response 400 {:error "title is required"})
      (mw/log-and-guard
        "create-context"
        (select-keys body [:title :short-title :sort-idx :hide-in-global-search])
        (json-response 201 {:id nil :title (:title body)})
        (fn [] (create-context-impl db body))))))

(defn- force-show-badge!
  "link-item-to-another-item! preserves :show-badge? on existing entries; this
  patches :data.contexts.<target>.show-badge? to the requested value so the
  upsert reflects the caller's intent."
  [db source-id target-id show-badge?]
  (let [item (datastore/get-item db {:id source-id})
        data (assoc-in (or (:data item) {})
                       [:contexts target-id :show-badge?] show-badge?)]
    (jdbc/execute! db
                   (sql/format {:update [:items]
                                :set {:data [:inline (json/generate-string data)]}
                                :where [:= :id [:inline source-id]]}))))

(defn- upsert-relation-impl
  [db source-item target-item show-badge?]
  (try (relations/link-item-to-another-item! db source-item target-item show-badge?)
       (force-show-badge! db (:id source-item) (:id target-item) show-badge?)
       (json-response (item->api (datastore/get-item db {:id (:id source-item)})))
       (catch Exception e
         (log/error e "REST API: upsert-relation failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn upsert-relation
  "PUT /rest/relations — upsert a relation between two items. JSON body:
  {\"source-id\" (required int), \"target-id\" (required int),
  \"show-badge\" (optional bool, default true — controls whether the badge for
  this relation is shown in the source item's context list)}. The relation is
  added to source-item's :data.contexts, with target-item as the owner. Idempotent.
  Returns 400 on missing/invalid ids, 404 if either item does not exist, 500
  otherwise. Gated by recording mode."
  [db req]
  (let [{:keys [source-id target-id show-badge]} (parse-json-body req)
        show-badge? (if (nil? show-badge) true (boolean show-badge))]
    (cond
      (not (and (integer? source-id) (integer? target-id)))
        (json-response 400 {:error "source-id and target-id are required integers"})
      (= source-id target-id)
        (json-response 400 {:error "source-id and target-id must differ"})
      :else
        (let [source-item (datastore/get-item db {:id source-id})
              target-item (datastore/get-item db {:id target-id})]
          (cond
            (nil? (:id source-item)) (json-response 404 {:error "source item not found"})
            (nil? (:id target-item)) (json-response 404 {:error "target item not found"})
            :else
              (mw/log-and-guard
                "upsert-relation"
                {:source-id source-id :target-id target-id :show-badge? show-badge?}
                (json-response (item->api source-item))
                (fn [] (upsert-relation-impl db source-item target-item show-badge?))))))))

(defn backfill-embeddings
  "POST /rest/backfill/embeddings — embed every item that has a non-empty
  description and a NULL embedding. Idempotent: items that already have an
  embedding are skipped, so it's safe to re-run (e.g. after Ollama was down
  during writes, or after UI-created items bypassed the ingestion hook).
  Synchronous — the request blocks until completion, so long runs tie up the
  connection. Gated by recording mode. Returns {:embedded N :failed M}. Per-
  item progress is logged server-side (tail logs/dev.out)."
  [db]
  (mw/log-and-guard
    "backfill-embeddings"
    {}
    (json-response {:embedded 0 :failed 0 :dry-run true})
    (fn []
      (try (json-response (backfill/backfill-missing! db))
           (catch Exception e
             (log/error e "REST API: backfill-embeddings failed")
             (json-response 500 {:error (.getMessage e)}))))))

(defn- candidates-for-related-deletion
  "Items that danger-mode 'delete related' targets, given the parent
  item. Mirrors the in-app related-items search but with q forced empty:
  honours the parent's stored view filters (selected secondary contexts,
  inverted/unassigned flags, search-mode, description filter)."
  [db parent]
  (let [view (-> parent :data :views :current)
        opts (assoc (select-keys view
                                 [:secondary-contexts-inverted
                                  :secondary-contexts-unassigned-selected
                                  :selected-secondary-contexts
                                  :search-mode
                                  :description-filter])
               :selected-item-id (:id parent))]
    (search/search-related-items db "" (:id parent) opts {})))

(defn- run-related-deletion!
  "Walk the candidate items and call repository.deletion/delete-item for
  each (with dry-run? when previewing). Returns the assembled response
  body — same shape for preview and real delete so both endpoints stay
  in lock-step."
  [db parent dry-run?]
  (let [items (candidates-for-related-deletion db parent)
        results (mapv (fn [{:keys [id title] :as item}]
                        (let [{:keys [status reason]}
                                (repository.deletion/delete-item db item dry-run?)]
                          (cond-> {:id id :title title :status status}
                            reason (assoc :reason reason))))
                      items)]
    {:requested (count items)
     :parent-id (:id parent)
     :parent-title (:title parent)
     :dry-run dry-run?
     :results results}))

(defn ^:no-describe deletion-preview-related-items
  "GET /rest/items/:id/related/deletion-preview — read-only preview of
  what POST /rest/items/:id/related/delete would do. Walks the same
  per-item code path as the real delete (with dry-run? = true), so the
  preview's :results — including which items would be skipped because
  they have children, multiply-referenced files, etc. — match what an
  actual delete would produce. Not gated. Unlisted (no /rest/describe)."
  [db id]
  (try (let [parent-id (Integer/parseInt id)
             parent (datastore/get-item db {:id parent-id})]
         (if-not (:id parent)
           (json-response 404 {:error "parent item not found"})
           (json-response (run-related-deletion! db parent true))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))
       (catch Exception e
         (log/error e "REST API: deletion-preview-related-items failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn ^:no-describe delete-related-items
  "POST /rest/items/:id/related/delete — delete every item that
  /related/deletion-preview returns for the same parent. The server
  recomputes the candidate set from the parent's stored view (q forced
  empty, secondary contexts honoured) and walks the SAME per-item path
  as the preview (just with dry-run? = false), so the two cannot drift.
  Gated by recording mode: when off, the request is dropped with
  :dropped true and an empty :results. Unlisted (no /rest/describe)."
  [db id]
  (try (let [parent-id (Integer/parseInt id)
             parent (datastore/get-item db {:id parent-id})]
         (if-not (:id parent)
           (json-response 404 {:error "parent item not found"})
           (mw/log-and-guard
             "delete-related-items"
             {:parent-id parent-id :parent-title (:title parent)}
             (json-response {:parent-id parent-id
                             :parent-title (:title parent)
                             :dry-run false
                             :results []
                             :dropped true})
             (fn [] (json-response (run-related-deletion! db parent false))))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))
       (catch Exception e
         (log/error e "REST API: delete-related-items failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn ^:no-describe toggle-recording-mode
  "POST /rest/recording-mode/toggle — toggle the write-gate. While ON, mutating
  endpoints execute; while OFF they log intent and return 403 {:dropped true}.
  Prefer the in-app shortcut Option+Shift+W for day-to-day toggling."
  []
  (let [now (mw/toggle!)]
    (log/info {:recording now} (str "RECORDING MODE " (if now "ON" "OFF")))
    (json-response {:recording now})))

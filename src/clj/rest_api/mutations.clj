(ns rest-api.mutations
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [clojure.string :as str]
            [cambium.core :as log]
            [cheshire.core :as json]
            [et.vp.ds :as datastore]
            [et.vp.ds.relations :as relations]
            [et.vp.ds.part-of :as part-of]
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
    (datastore/update-context-description db {:id (:id item) :description description} "api")
    item))

(defn- collision-refusal
  "The answer to a POST whose title names something the graph already holds.

  POST /api/items creates, and creating is the whole of what it does. That is
  what the import door is open for: a request that can only add cannot rewrite
  anything that is already there, and so it is safe to let through a shut gate.
  An ingester handed a URL it has seen before answers with the item it found,
  and everything this endpoint would do next — filing it under the contexts on
  the request, replacing its description, moving its sort index — is that
  rewrite. So it is refused, and nothing is written.

  409 and not 400: the request was well formed, and what stands in the way is
  the state of the graph. The item that was found is named in the body, so a
  caller can tell a collision from a failure and can see what it collided with."
  [item]
  (log/info {:event "create-item-collision" :id (:id item) :title (:title item)}
            (str "REST create-item refused: already held as item " (:id item)))
  (json-response 409
                 {:error (str "already in the graph as item " (:id item)
                              " (\"" (:title item) "\"). POST /api/items creates; it does not"
                              " change an item that is already there. PUT /api/items/" (:id item)
                              " replaces a description and PUT /api/relations files an item under"
                              " another context — both need recording mode on, which the import"
                              " door does not turn on for them.")
                  :collision true
                  :existing-item-id (:id item)
                  :existing-item-title (:title item)}))

(defn- create-item-impl
  [db {:keys [title context-ids sort-idx description] :as _body} scrape?]
  (try (let [context-ids (set context-ids)
             primary-id (first context-ids)
             rest-ids (disj context-ids primary-id)
             item (if scrape?
                    (insertion/insert-item db title {:id primary-id} rest-ids "api")
                    (datastore/new-item db title "" context-ids nil "api"))]
         ;; Before apply-sort-idx and apply-description, which are writes: a
         ;; refusal that had already changed the item would not be one.
         (if (:previously-existing-item? item)
           (collision-refusal item)
           (let [item (apply-sort-idx db item sort-idx)
                 item (apply-description db item description)]
             (when (map? item)
               (embed-item-best-effort! db item))
             (if (map? item)
               (json-response 201 (item->api item))
               (json-response 201 {:created true})))))
       (catch Exception e
         (log/error e "REST API: create-item failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn- imports-context-id
  "The numeric id of the context whose human-readable id is \"imports\", or nil
  when there is none. Looked up and never created: an endpoint that could
  conjure the context which opens its own gate would not be gated at all."
  [db]
  (try (:id (first (search/find-items-by-ids db {:human-readable-ids ["imports"]})))
       (catch Exception e
         (log/error e "REST API: could not look up the 'imports' context")
         nil)))

(defn- names-imports?
  "True when \"imports\" is among the contexts `context-ids` names, whatever else
  is named alongside it. That is what POST /api/items is let through a shut
  recording gate for. The door stands on the handle and on nothing else: a
  context merely titled \"Imports\" is not it."
  [db context-ids]
  (when-let [imports-id (imports-context-id db)]
    (contains? (set context-ids) imports-id)))

(defn create-item
  "POST /api/items — create a new item. JSON body: {\"title\" (required),
  \"context-ids\" (required, at least one), \"description\" (optional),
  \"sort-idx\" (optional int, e.g. a page number).

  Query parameter \"scrape\": with ?scrape=true a title that is a URL is handed
  to the ingesters, which fetch the page and keep what they find there — an item
  that came about that way is stamped provenance \"scraper\". Without it, which
  is the default, nothing is fetched: the item is stored exactly as it was sent,
  URL-shaped title and all, and is stamped \"api\". A title no ingester
  recognises is stored as it came in either way, and is \"api\" either way —
  the stamp records whether the text was scraped, not whether it was asked for.

  This endpoint creates and does nothing else. A title an ingester recognises
  as something the graph already holds is refused with 409 {\"collision\": true}
  naming the item that was found, and nothing is written — not the contexts on
  the request, not the description, not the sort index. Filing an existing item
  under another context is PUT /api/relations and replacing its description is
  PUT /api/items/:id; both are gated by recording mode.

  Gated by recording mode: when off, the write is logged and dropped with 201
  {:created true} stub. One exception: when a context carrying the
  human-readable id \"imports\" exists and \"context-ids\" names it, the item is
  written with the gate still shut, whatever else is named alongside. That is
  the import door. It stands on the handle and on nothing else — a context
  merely titled \"Imports\" is not the door, and while no context carries the
  handle there is no door at all. What keeps the door safe to leave open is the
  paragraph above: a request that comes through it can add to the graph and can
  do nothing else to it."
  [db req]
  (let [body (parse-json-body req)
        scrape? (= "true" (get-in req [:params "scrape"]))]
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
           :scrape? scrape?
           :description-length (count (or (:description body) ""))}
          (json-response 201 {:created true})
          (fn [] (names-imports? db (:context-ids body)))
          ;; The same write either way: what POST does is create, and creating
          ;; is all it does, so there is nothing for the door to hold back.
          (fn [_bypassed?] (create-item-impl db body scrape?))))))

(defn- update-item-description-impl
  [db id description file-under-imports-id]
  (try (let [updated (datastore/update-context-description
                       db
                       {:id id :description description}
                       "api")
             ;; Only ever set on the door path. An item that arrived that way is
             ;; filed in Imports so it turns up where things that came in from
             ;; outside turn up; a write through an open gate is the owner's own
             ;; hand and does not need announcing to itself.
             updated (if file-under-imports-id
                       (insertion/ensure-contexts! db updated #{file-under-imports-id})
                       updated)]
         (embed-item-best-effort! db updated)
         (json-response (item->api updated)))
       (catch Exception e
         (log/error e "REST API: update-item-description failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn- description-refusal
  "The answer to a PUT, made with the gate shut, at an item that already carries
  a description.

  Not the ordinary dropped write. A drop is for a request the gate simply does
  not admit, and it says so by answering with the item as it would have looked,
  which leaves a caller unable to tell a write from a silence. This one is a
  standing refusal: the text is there, and no amount of trying from out here
  will replace it. Saying so out loud lets a caller stop rather than send it
  again, and it is the same 409 POST answers a collision with — both mean the
  graph already holds this and the way in only adds."
  [item]
  (json-response 409
                 {:error (str "item " (:id item) " (\"" (:title item) "\") already has a"
                              " description. With recording off, PUT /api/items/:id writes a"
                              " description that is not there yet and does not replace one."
                              " Replacing it is done in the app.")
                  :collision true
                  :item-id (:id item)
                  :item-title (:title item)}))

(defn update-item-description
  "PUT /api/items/:id — replace an item's description. JSON body: {\"description\"}.
  404 if the item does not exist.

  Gated by recording mode, with a door beside the one POST /api/items has: when
  a context carrying the human-readable id \"imports\" exists and the item being
  written to has no description yet, the write goes through with the gate still
  shut. Both halves are the same idea as over there — through a door you may add
  and only add. An item that already has a description is not writable this way
  at all, so nothing a caller sends can displace text that is already in the
  graph, and the gate has to be opened from the app to replace one.

  Trying it anyway is answered with 409 {\"collision\": true} rather than the
  dropped-write stub, so a caller can tell a standing refusal from a gate that
  happens to be shut, and stop instead of sending it again. The stub is still
  what a request gets when it is only gated — an item with no description and no
  context carrying the handle, where there is no door rather than a refusal.

  An item written through the door is also filed under \"imports\", if it is not
  already, so what came in from outside turns up where the rest of it does. That
  happens on the door path alone: with recording on this endpoint replaces a
  description and touches nothing else."
  [db id req]
  (try (let [id (Integer/parseInt id)
             {:keys [description]} (parse-json-body req)]
         (cond
           (not description)
             (json-response 400 {:error "description is required"})
           :else
             (let [item (datastore/get-item db {:id id})
                   ;; Looked up at most once, and not at all unless the gate is
                   ;; shut and something asks — the common path does not pay for
                   ;; it, and the answer is wanted twice on the path that does.
                   imports-id (delay (imports-context-id db))]
               (if-not item
                 (json-response 404 {:error "Item not found"})
                 (mw/log-and-guard
                   "update-item-description"
                   {:id id :title (:title item) :description-length (count description)}
                   ;; Reached only with the gate shut and the door unopened, so
                   ;; the two cases can be told apart here rather than by asking
                   ;; a second time how the gate stands. A non-empty description
                   ;; is what closes the door, and it is the refusal; anything
                   ;; else is an ordinary drop.
                   (if (str/blank? (:description item))
                     (json-response (item->api (assoc item :description description)))
                     (description-refusal item))
                   (fn [] (and @imports-id (str/blank? (:description item))))
                   (fn [bypassed?]
                     (update-item-description-impl db
                                                   id
                                                   description
                                                   (when bypassed? @imports-id))))))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))))

(defn- context-extras-set
  [{:keys [short-title human-readable-id sort-idx hide-in-global-search]}]
  (cond-> {}
    short-title
      (assoc :short_title [:inline short-title])
    ;; Silently drop a digits-only human-readable-id — the rest of the create
    ;; still goes through. (Read-side dispatcher uses the digit/non-digit split
    ;; to route ?id=… so an all-digits handle here would be unreachable.)
    (and (string? human-readable-id) (re-find #"\D" human-readable-id))
      (assoc :human_readable_id [:inline human-readable-id])
    sort-idx
      (assoc :sort_idx [:inline sort-idx])
    (true? hide-in-global-search)
      (assoc :hide_in_global_search [:inline true])))

(defn- create-context-impl
  [db {:keys [title] :as body}]
  (try (let [ctx (datastore/new-context db {:title title} "api")
             context-extras-set (context-extras-set body)
             ctx (if (seq context-extras-set) (patch-item! db (:id ctx) context-extras-set) ctx)]
         (json-response 201 (item->api ctx)))
       (catch Exception e
         (log/error e "REST API: create-context failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn create-context
  "POST /api/contexts — create a new context (item with is_context=true).
  JSON body: {\"title\" (required), \"short-title\" (optional),
  \"human-readable-id\" (optional string — stable handle for GET /api/items?id=…;
  must be unique and contain at least one non-digit character),
  \"sort-idx\" (optional int), \"hide-in-global-search\" (optional bool — when
  true, the context is excluded from global contexts search). Gated by
  recording mode."
  [db req]
  (let [body (parse-json-body req)]
    (if-not (:title body)
      (json-response 400 {:error "title is required"})
      (mw/log-and-guard
        "create-context"
        (select-keys body [:title :short-title :human-readable-id :sort-idx :hide-in-global-search])
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
  [db source-item target-item show-badge? part-of]
  (try (relations/link-item-to-another-item! db source-item target-item show-badge? part-of)
       (force-show-badge! db (:id source-item) (:id target-item) show-badge?)
       (json-response (item->api (datastore/get-item db {:id (:id source-item)})))
       (catch clojure.lang.ExceptionInfo e
         (if-let [msg (part-of/cycle-refusal e)]
           (do (log/warn {:event "part-of-cycle-refused"
                          :source-id (:id source-item)
                          :target-id (:id target-item)}
                         msg)
               (json-response 409 {:error msg :part-of-cycle (:path (ex-data e))}))
           (do (log/error e "REST API: upsert-relation failed")
               (json-response 500 {:error (.getMessage e)}))))
       (catch Exception e
         (log/error e "REST API: upsert-relation failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn upsert-relation
  "PUT /api/relations — upsert a relation between two items. JSON body:
  {\"source-id\" (required int), \"target-id\" (required int),
  \"show-badge\" (optional bool, default true — controls whether the badge for
  this relation is shown in the source item's context list),
  \"is-part-of\" (optional bool — mark this relation as a part-of edge, meaning
  target-item is the whole and source-item one of its parts; omitted, an
  existing relation keeps the standing it had),
  \"part-of-sort-idx\" (optional int — where source-item sits among the parts of
  target-item. **Any** integer is accepted and the parts are listed by it
  ascending. -1 is the one reserved value: it is the default, it means unplaced,
  and it sorts *after* every part that carries an index rather than ahead of 0.
  Every other negative is an ordinary index and does sort ahead of 0, so a
  deliberate -2 places a part in front of everything. Independent of every other
  sort index, so a node with several wholes can be placed differently under
  each)}. The relation is
  added to source-item's :data.contexts, with target-item as the owner. Idempotent.
  Returns 400 on missing/invalid ids, 404 if either item does not exist, 409 when
  the part-of edge would close a loop (the body names the path), 500 otherwise.
  Gated by recording mode."
  [db req]
  (let [{:keys [source-id target-id show-badge is-part-of part-of-sort-idx]} (parse-json-body req)
        show-badge? (if (nil? show-badge) true (boolean show-badge))
        part-of (cond-> {}
                  (some? is-part-of) (assoc :is-part-of? (boolean is-part-of))
                  (some? part-of-sort-idx) (assoc :part-of-sort-idx part-of-sort-idx))]
    (cond
      (not (and (integer? source-id) (integer? target-id)))
        (json-response 400 {:error "source-id and target-id are required integers"})
      (= source-id target-id)
        (json-response 400 {:error "source-id and target-id must differ"})
      (and (some? part-of-sort-idx) (not (integer? part-of-sort-idx)))
        (json-response 400 {:error "part-of-sort-idx must be an integer"})
      :else
        (let [source-item (datastore/get-item db {:id source-id})
              target-item (datastore/get-item db {:id target-id})]
          (cond
            (nil? (:id source-item)) (json-response 404 {:error "source item not found"})
            (nil? (:id target-item)) (json-response 404 {:error "target item not found"})
            :else
              (mw/log-and-guard
                "upsert-relation"
                (merge {:source-id source-id :target-id target-id :show-badge? show-badge?}
                       part-of)
                (json-response (item->api source-item))
                (fn [] (upsert-relation-impl db source-item target-item show-badge? part-of))))))))

(defn backfill-embeddings
  "POST /api/backfill/embeddings — embed every item that has a non-empty
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
  "Items that danger-mode 'delete related' targets, given the context the
  operation runs from. Mirrors the in-app related-items search but with q
  forced empty: honours the context's stored view filters (selected
  secondary contexts, inverted/unassigned flags, search-mode, description
  filter)."
  [db context]
  (let [view (-> context :data :views :current)
        opts (assoc (select-keys view
                                 [:secondary-contexts-inverted
                                  :secondary-contexts-unassigned-selected
                                  :selected-secondary-contexts
                                  :search-mode
                                  :description-filter])
               :selected-item-id (:id context))]
    (search/search-related-items db "" (:id context) opts {})))

(defn- run-related-deletion!
  "Compute a cascading deletion plan for the context's secondary-filtered
  items and (unless dry-run?) execute it. Returns the assembled response
  body — same shape for preview and real delete so the two stay in
  lock-step. Shape:
    {:context-id, :context-title, :dry-run,
     :primary  [{:id :title :status (\"deleted\"|\"skipped\") :reason?}]
     :cascade  [{:id :title :status (\"deleted\"|\"skipped\") :reason?}]
     :unlinked [{:id :title :keep-reasons [...] :unlinked-from [...]}]}"
  [db context dry-run?]
  (let [items (candidates-for-related-deletion db context)
        plan (repository.deletion/plan-and-execute! db items dry-run? (:id context))]
    (merge {:context-id (:id context)
            :context-title (:title context)}
           plan)))

(defn ^:no-describe deletion-preview-related-items
  "GET /api/items/:id/related/deletion-preview — read-only preview of
  what POST /api/items/:id/related/delete would do. :id is the context
  the operation runs from. Walks the same planner (with dry-run? = true)
  so the preview's three buckets — :primary, :cascade, :unlinked — match
  what an actual delete would produce. Not gated. Unlisted (no
  /api/describe)."
  [db id]
  (try (let [context-id (Integer/parseInt id)
             context (datastore/get-item db {:id context-id})]
         (if-not (:id context)
           (json-response 404 {:error "context not found"})
           (json-response (run-related-deletion! db context true))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))
       (catch Exception e
         (log/error e "REST API: deletion-preview-related-items failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn ^:no-describe delete-related-items
  "POST /api/items/:id/related/delete — runs the cascade plan for the
  context's secondary-filtered items and executes it. :id is the
  context the operation runs from. Each item in the :primary bucket
  (matching the context's stored view) is unlinked from every relation
  it touches; neighbors are then re-classified — orphaned ones
  cascade-delete (bucket :cascade), surviving ones land in :unlinked
  with the reasons they were kept. Gated by recording mode: when off,
  returns :dropped true and empty buckets. Unlisted (no /api/describe)."
  [db id]
  (try (let [context-id (Integer/parseInt id)
             context (datastore/get-item db {:id context-id})]
         (if-not (:id context)
           (json-response 404 {:error "context not found"})
           (mw/log-and-guard
             "delete-related-items"
             {:context-id context-id :context-title (:title context)}
             (json-response {:context-id context-id
                             :context-title (:title context)
                             :dry-run false
                             :primary []
                             :cascade []
                             :unlinked []
                             :dropped true})
             (fn [] (json-response (run-related-deletion! db context false))))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))
       (catch Exception e
         (log/error e "REST API: delete-related-items failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn ^:no-describe toggle-recording-mode
  "POST /api/recording-mode/toggle — toggle the write-gate. While ON, mutating
  endpoints execute; while OFF they log intent and return 403 {:dropped true}.
  Prefer the in-app shortcut Option+Shift+W for day-to-day toggling."
  []
  (let [now (mw/toggle!)]
    (log/info {:recording now} (str "RECORDING MODE " (if now "ON" "OFF")))
    (json-response {:recording now})))

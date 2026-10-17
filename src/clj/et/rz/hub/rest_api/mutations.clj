(ns et.rz.hub.rest-api.mutations
  (:require [et.rz.hub.db :as db]
            [honey.sql :as sql]
            [clojure.string :as str]
            [cambium.core :as log]
            [cheshire.core :as json]
            [et.rz.hub.ds :as datastore]
            [et.rz.hub.ds.relations :as relations]
            [et.rz.hub.ds.part-of :as part-of]
            [et.rz.hub.ds.search :as search]
            [et.rz.hub.provenance :as provenance]
            [et.rz.hub.rest-api.middleware :as mw]
            [et.rz.hub.repository.insertion :as insertion]
            [et.rz.hub.repository.deletion]
            [et.rz.hub.semsearch.backfill :as backfill]
            [et.rz.hub.rest-api.util :refer [json-response parse-json-body item->api]]))

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
  (db/execute! db
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
  "The write with the gate open: the owner's own hand, so nothing is checked and
  nothing is filed. Both doors write through `write-without-degrading-impl`."
  [db id description]
  (try (let [updated (datastore/update-context-description
                       db
                       {:id id :description description}
                       "api")]
         (embed-item-best-effort! db updated)
         (json-response (item->api updated)))
       (catch Exception e
         (log/error e "REST API: update-item-description failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn- degradation-refusal
  "The answer to a gate-shut PUT whose description would lower lines of the
  owner's. A standing refusal and not a drop, like the other doors' 409s: this
  text will never land from out here, and a caller should be able to tell that
  from a gate that is merely shut. The lines are in the body, one-based in the
  description as it stands now, so the caller can see what it was about to cost
  and write something that leaves them be."
  [item degraded]
  (json-response 409
                 {:error (str "item " (:id item) " (\"" (:title item) "\"): with recording off,"
                              " PUT /api/items/:id may not lower any line of the owner's, and this"
                              " description would lower " (count degraded) " of them (see"
                              " \"degraded\": line, text, caution before, caution after, null when"
                              " the line would be gone). Leave his lines as they are and where they"
                              " are, and append after his text; text he wrote in one go is one"
                              " stretch, blank lines and all, so a paragraph put between two of his"
                              " is inside it. Insert between only where the item's caution ranges"
                              " show two separate stretches of his.")
                  :refused "degrades-owner-lines"
                  :item-id (:id item)
                  :item-title (:title item)
                  :degraded degraded}))

(defn- degradations
  [db id description]
  (provenance/degradations (:versions (datastore/get-description-history db {:id id}))
                           description))

(defn- write-without-degrading-impl
  "The write behind both of PUT's doors. Asked again inside the transaction that
  writes, so what is checked is what is written over: a version that landed
  between the gate's look and this one is part of the history this one reads,
  and if it makes the answer no, the answer is no. The import door comes through
  here too: its description was empty at the gate's look, which answers [], but
  an app save in between would not be.

  `file-under-imports-id`, set on the import door's path alone, files the item
  there once the description is in, so what came in from outside turns up where
  the rest of it does; a write through an open gate is the owner's own hand and
  does not need announcing to itself. After the transaction and not in it: the
  filing adds a context and cannot lower a line, so it does not need the check."
  [db item description file-under-imports-id]
  (try (let [result (db/with-transaction [tx db]
                      (let [degraded (degradations tx (:id item) description)]
                        (if (seq degraded)
                          (do (log/warn {:intent "update-item-description"
                                         :id (:id item)
                                         :refused-in-transaction true
                                         :degraded-count (count degraded)}
                                        (str "REST update-item-description: refused inside the"
                                             " transaction, nothing written (the gate-bypassed"
                                             " line above was the gate's look)"))
                              {:refusal (degradation-refusal item degraded)})
                          {:updated (datastore/update-context-description
                                      tx
                                      {:id (:id item) :description description}
                                      "api")})))]
         (or (:refusal result)
             (let [updated (cond-> (:updated result)
                             file-under-imports-id
                               (#(insertion/ensure-contexts! db % #{file-under-imports-id})))]
               ;; Outside the transaction: it calls out to Ollama, and a write
               ;; lock is not something to hold across a network round trip.
               (embed-item-best-effort! db updated)
               (json-response (item->api updated)))))
       (catch Exception e
         (log/error e "REST API: update-item-description failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn update-item-description
  "PUT /api/items/:id — replace an item's description. JSON body: {\"description\"}.
  404 if the item does not exist.

  Gated by recording mode, with two doors. Through either you may only add, in
  the sense that nothing of the owner's is worth less afterwards.

  The import door: when a context carrying the human-readable id \"imports\"
  exists and the item has no description yet, the description is written and
  the item is also filed under \"imports\", if it is not already, so what came
  in from outside turns up where the rest of it does.

  The description door: otherwise, the description is written when it lowers
  no line of the owner's — every line of his in the current description is still
  there afterwards and none would be read with less caution than now. Changing a
  line of his counts as removing it; so does moving it; one line put into the
  middle of a stretch of his lowers every line of his in that stretch; a
  recurring line of his (a blank one) between two rewritten neighbours counts as
  gone. Appending, and rewriting stretches no hand of his is in, go through.
  This door files nothing and needs no \"imports\" context. The check and the
  write share one transaction.

  A description this door turns away is answered 409 {\"refused\":
  \"degrades-owner-lines\", \"degraded\": [{\"line\", \"text\", \"before\",
  \"after\"}]}, \"after\" null for a line that would be gone, \"line\" one-based in
  the description as it stands. That is a standing refusal: changing what the
  owner wrote is done in the app. There is no plain drop on this endpoint: with
  the gate shut a description either goes through a door or is refused.

  Both doors are logged with gate-bypassed. With recording on this endpoint
  replaces a description and touches nothing else."
  [db id req]
  (try (let [id (Integer/parseInt id)
             {:keys [description]} (parse-json-body req)]
         (cond
           (not (string? description))
             (json-response 400 {:error "description is required (a string)"})
           :else
             (let [item (datastore/get-item db {:id id})
                   ;; Looked up at most once, and not at all unless the gate is
                   ;; shut and something asks — the common path does not pay for
                   ;; it, and the answer is wanted twice on the path that does.
                   imports-id (delay (imports-context-id db))
                   import-door? (delay (boolean (and @imports-id
                                                     (str/blank? (:description item)))))
                   ;; The gate's look. The write looks again, in its transaction.
                   degraded (delay (degradations db id description))]
               (if (nil? (:id item))
                 (json-response 404 {:error "Item not found"})
                 ;; The dropped response is a delay, and `force` takes it out at
                 ;; the end: it is the refusal, and working out a refusal costs
                 ;; the history and the alignment, which an open gate should not
                 ;; pay for. `force` hands any other answer back unchanged.
                 (force
                   (mw/log-and-guard
                     "update-item-description"
                     {:id id :title (:title item) :description-length (count description)}
                     (delay (degradation-refusal item @degraded))
                     (fn [] (or @import-door? (empty? @degraded)))
                     (fn [bypassed?]
                       (cond
                         (not bypassed?) (update-item-description-impl db id description)
                         @import-door? (write-without-degrading-impl db item description @imports-id)
                         :else (write-without-degrading-impl db item description nil)))))))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))))

(defn- imports-handle?
  "True when `handle` is the human-readable id that opens the import doors."
  [handle]
  (= "imports" handle))

(defn- imports-handle-refusal
  "The standing refusal for a gate-shut request that would make a context of the
  handle \"imports\" — creating one, or turning the item that carries it into
  one. See `imports-context-id` for why that handle is never conjured from out
  here: the doors it opens would no longer be gated by anything."
  [& what]
  (json-response 409
                 {:error (str (apply str what) " while recording is off. That handle opens"
                              " the import doors, and an agent that could make it would be"
                              " opening its own gate. It is done in the app.")
                  :refused "imports-handle"}))

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
  recording mode.

  With the gate shut the context is created all the same, and the log line
  carries `gate-bypassed`. A new context is a new item and nothing else: it
  rewrites nothing that is there, so it is one more door of the kind POST
  /api/items has, and it is filed nowhere it was not asked to be. The one thing
  it may not do is take the handle \"imports\" — that handle is what opens the
  import doors, and a request that could conjure it would be opening its own
  gate. Asking for it with the gate shut is answered 409 {\"refused\":
  \"imports-handle\"}, and nothing is written."
  [db req]
  (let [body (parse-json-body req)]
    (if-not (:title body)
      (json-response 400 {:error "title is required"})
      (mw/log-and-guard
        "create-context"
        (select-keys body [:title :short-title :human-readable-id :sort-idx :hide-in-global-search])
        ;; Reached only with the gate shut and the door unopened, and the door
        ;; stays unopened for one request alone: the one naming the handle.
        (imports-handle-refusal "POST /api/contexts may not create the context with the handle"
                                " \"imports\"")
        (fn [] (not (imports-handle? (:human-readable-id body))))
        (fn [_bypassed?] (create-context-impl db body))))))

(defn- is-context-refusal
  [refused item error]
  (json-response 409 {:error error
                      :refused refused
                      :item-id (:id item)
                      :item-title (:title item)}))

(defn- machine-made-throughout?
  [db item]
  (provenance/machine-made-throughout?
    (:versions (datastore/get-description-history db {:id (:id item)}))))

(defn- shut-gate-is-context-refusal
  "Why a gate-shut flip of `item` to `is-context?` was not let through. Only
  reached when it was not, so there is always a reason, and each is a standing
  one: sending it again from out here changes nothing."
  [item is-context?]
  (cond
    (not is-context?)
      (is-context-refusal "demote" item
                          (str "item " (:id item) " (\"" (:title item) "\") is a context. With"
                               " recording off, PUT /api/items/:id/is-context turns an item into a"
                               " context and never the other way: a context is a place things are"
                               " filed, and taking it away is done in the app."))
    (imports-handle? (:human_readable_id item))
      (imports-handle-refusal "PUT /api/items/" (:id item) "/is-context may not turn the item with"
                              " the handle \"imports\" into a context")
    :else
      (is-context-refusal "not-machine-made" item
                          (str "item " (:id item) " (\"" (:title item) "\") has a version that is"
                               " the owner's own (source \"app\" or \"obsidian\", or no source at"
                               " all). With recording off, only an item every version of which"
                               " was written by a machine can be made a context."))))

(defn- set-is-context-impl
  [db item is-context?]
  (try
    ;; The app's own rule, kept by reusing the app's own switch: a context that
    ;; is filed under nothing cannot become an item, which would leave it filed
    ;; nowhere. The switch declines that silently; out here it is said.
    (if (and (not is-context?) (empty? (get-in item [:data :contexts])))
      (is-context-refusal "in-no-context" item
                          (str "item " (:id item) " (\"" (:title item) "\") is a context filed under"
                               " no other context. Made an item it would sit nowhere, so it stays a"
                               " context. File it under a context first (PUT /api/relations)."))
      (json-response (item->api (datastore/switch-between-item-and-context! db item))))
    (catch Exception e
      (log/error e "REST API: set-is-context failed")
      (json-response 500 {:error (.getMessage e)}))))

(defn set-is-context
  "PUT /api/items/:id/is-context — turn an item into a context, or a context
  into an item. JSON body: {\"is-context\" (required bool)}. Answers with the
  item as it now stands. 400 when \"is-context\" is not a boolean, 404 when the
  item does not exist. An item already in the state asked for is answered 200
  with the item and \"unchanged\": true, and nothing is written.

  The same switch the app uses, with its rule: a context filed under no other
  context is not turned into an item, since it would be filed nowhere — 409
  {\"refused\": \"in-no-context\"}.

  Gated by recording mode, with a door. With the gate shut an item is turned
  into a context when it is machine-made throughout: every version of it, the
  current one and each in its history, has a source that is not the owner's
  (not \"app\", not \"obsidian\", and not blank — a version with no source is read
  as his, so old items never qualify). Title changes stamp the source too, so
  that covers title and description alike. What no hand of his has touched is
  not a thing whose standing he decided. Logged with `gate-bypassed`.

  Everything else asked with the gate shut is a standing refusal, 409 with
  \"refused\" saying which: \"not-machine-made\" for an item a version of which is
  his; \"imports-handle\" for the item carrying the handle \"imports\", which is
  never made a context from out here (see POST /api/contexts); \"demote\" for any
  context to item, which takes a place away and so is done in the app."
  [db id req]
  (try (let [id (Integer/parseInt id)
             is-context (:is-context (parse-json-body req))]
         (if-not (boolean? is-context)
           (json-response 400 {:error "is-context is required (true or false)"})
           (let [item (datastore/get-item db {:id id})]
             (cond
               (nil? (:id item))
                 (json-response 404 {:error "Item not found"})
               (= is-context (boolean (:is_context item)))
                 (json-response (assoc (item->api item) :unchanged true))
               :else
                 (mw/log-and-guard
                   "set-is-context"
                   {:id id :title (:title item) :is-context is-context}
                   (shut-gate-is-context-refusal item is-context)
                   (fn [] (and is-context
                               (not (imports-handle? (:human_readable_id item)))
                               (machine-made-throughout? db item)))
                   (fn [_bypassed?] (set-is-context-impl db item is-context)))))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))))

(def ^:private patchable-properties
  "What PATCH /api/items/:id may change, named as GET /api/items/:id serves it.
  `:column` is the key of the row `datastore/get-item` returns, which is what
  `write-item-rows!` writes from. `:problem` takes a proposed new value and
  answers nil when it is acceptable, else what was expected."
  (let [string-or-null (fn [v] (when-not (or (nil? v) (string? v)) "a string, or null"))]
    {:title {:column :title
             :problem (fn [v] (when-not (and (string? v) (not (str/blank? v)))
                                "a non-blank string"))}
     :short-title {:column :short_title :problem string-or-null}
     :tags {:column :tags :problem string-or-null}
     :annotation {:column :annotation :problem string-or-null}
     :date {:column :date
            :problem (fn [v] (when-not (or (nil? v)
                                           (and (string? v)
                                                (re-matches #"\d{4}-\d{2}-\d{2}" v)
                                                (try (java.time.LocalDate/parse v)
                                                     (catch Exception _ nil))))
                               "a date as YYYY-MM-DD, or null"))}
     :sort-idx {:column :sort_idx
                :problem (fn [v] (when-not (integer? v) "an integer"))}
     :hide-in-global-search {:column :hide_in_global_search
                             :problem (fn [v] (when-not (boolean? v) "true or false"))}
     :human-readable-id {:column :human_readable_id
                         :problem (fn [v] (when-not (or (nil? v)
                                                        (and (string? v) (re-find #"\D" v)))
                                            (str "a string with at least one non-digit character,"
                                                 " or null")))}}))

(def ^:private elsewhere
  "Properties an item has that PATCH does not change, and where that is done."
  {:description "PUT /api/items/:id"
   :is-context "PUT /api/items/:id/is-context"
   :contexts "PUT /api/relations"
   :part-of "PUT /api/relations"})

(defn- patch-changes-problem
  "What is wrong with the \"changes\" of a PATCH body, as a message, or nil when
  it is well formed. Looks at the request alone, so the answer is the same with
  the gate open or shut and whether or not the item exists."
  [changes]
  (cond
    (not (and (map? changes) (seq changes)))
      "changes is required: an object of at least one property, each {\"old\": …, \"new\": …}"
    :else
      (some (fn [[prop change]]
              (let [spec (get patchable-properties prop)]
                (cond
                  (nil? spec)
                    (str "\"" (name prop) "\" is not a property PATCH changes"
                         (when-let [where (elsewhere prop)] (str " — that is " where))
                         ". Properties: " (str/join ", " (map name (keys patchable-properties))))
                  (not (and (map? change) (contains? change :old) (contains? change :new)))
                    (str "\"" (name prop) "\" needs both \"old\" and \"new\" (null is a value)")
                  :else
                    (when-let [expected ((:problem spec) (:new change))]
                      (str "\"" (name prop) "\": \"new\" must be " expected)))))
            changes)))

(defn- patch-mismatch-refusal
  [item mismatched]
  (log/info {:intent "patch-item" :id (:id item) :refused "old-values-differ"
             :mismatched (mapv name (keys mismatched))}
            "REST patch-item: refused, nothing written")
  (json-response 409
                 {:error (str "item " (:id item) ": \"old\" is not what is stored now for "
                              (str/join ", " (map name (keys mismatched)))
                              ". Nothing was written, not even the properties whose \"old\" was right."
                              " \"mismatched\" gives, for each, what was sent and what is there now: read"
                              " the item again (GET /api/items/" (:id item) ") and send the PATCH over"
                              " with current values, if the change still makes sense.")
                  :refused "old-values-differ"
                  :item-id (:id item)
                  :mismatched mismatched}))

(defn- patch-handle-taken-refusal
  [item handle]
  (json-response 409
                 {:error (str "human-readable-id \"" handle "\" belongs to another item already,"
                              " and it is unique. Nothing was written.")
                  :refused "human-readable-id-taken"
                  :item-id (:id item)}))

(defn- patch-item-impl
  "The check and the write, in one transaction: what is compared is what is
  written over, so a save that lands between a caller's read and this request
  makes the answer no, and a save that lands between the comparison and the
  write cannot. Nothing is written unless every \"old\" matched."
  [db id changes]
  (try
    (let [result
          (db/with-transaction [tx db]
            (let [current (datastore/get-item tx {:id id})
                  served (item->api current)
                  mismatched (into {}
                                   (keep (fn [[prop {:keys [old]}]]
                                           (let [now (get served prop)]
                                             (when (not= old now)
                                               [prop {:sent old :current now}]))))
                                   changes)
                  effective (into {} (remove (fn [[prop {:keys [new]}]] (= new (get served prop))))
                                  changes)
                  handle (get-in effective [:human-readable-id :new])]
              (cond
                (nil? (:id current))
                  {:response (json-response 404 {:error "Item not found"})}
                (seq mismatched)
                  {:response (patch-mismatch-refusal current mismatched)}
                (and handle
                     (db/execute-one! tx ["SELECT id FROM items WHERE human_readable_id = ? AND id <> ?"
                                          handle id]))
                  {:response (patch-handle-taken-refusal current handle)}
                (empty? effective)
                  {:response (json-response (item->api current))}
                :else
                  ;; Every field is written, so the item it starts from is the whole
                  ;; current one. The handle is dropped from it, because the writer
                  ;; clears or sets it whenever the key is there at all.
                  (let [item (reduce-kv (fn [m prop {:keys [new]}]
                                          (assoc m (get-in patchable-properties [prop :column]) new))
                                        (dissoc current :human_readable_id)
                                        effective)
                        {:keys [display-title-changed?]} (datastore/write-item-rows! tx item "api")]
                    {:updated (datastore/get-item tx {:id id})
                     :display-title-changed? display-title-changed?}))))]
      (or (:response result)
          (let [updated (:updated result)]
            ;; After the commit: `update-item` does this on a future, which would
            ;; outlive the transaction's connection.
            (when (:display-title-changed? result)
              (try (relations/update-collection-title-in-collection-items-for-children
                     db id (:title updated) (:short_title updated))
                   (catch Exception e
                     (log/error e "REST API: patch-item could not carry the title to its children"))))
            (json-response (item->api updated)))))
    (catch Exception e
      (log/error e "REST API: patch-item failed")
      (json-response 500 {:error (.getMessage e)}))))

(defn patch-item
  "PATCH /api/items/:id — change properties of an item by compare-and-set.
  JSON body: {\"changes\": {<property>: {\"old\": …, \"new\": …}, …}} — one
  property or several. Answers with the item as it now stands.

  Properties, named and shaped as GET /api/items/:id serves them: \"title\"
  (non-blank string), \"short-title\", \"tags\" and \"annotation\" (string or
  null), \"date\" (YYYY-MM-DD or null), \"sort-idx\" (integer), \"hide-in-global-search\"
  (boolean), \"human-readable-id\" (null, or a string with at least one non-digit
  character; unique). null as \"new\" clears the property. Not here: the
  description (PUT /api/items/:id), is-context (PUT /api/items/:id/is-context),
  contexts and part-of (PUT /api/relations), timestamps, embedding, data.

  \"old\" is what the property is now, exactly as GET serves it, and a property GET
  leaves out is null: no human-readable-id, annotation or date is null, and so
  is a hide-in-global-search that is false — GET only says it when true. Only
  when every \"old\" matches is any \"new\" written, and then all of them are, in
  one transaction with the comparison. Otherwise 409 {\"refused\":
  \"old-values-differ\", \"mismatched\": {<property>: {\"sent\", \"current\"}}}
  lists each property whose \"old\" was wrong with what is there now, and
  nothing is written. A 409 is for the caller to read the item again and decide.
  A \"human-readable-id\" another item holds is 409 {\"refused\":
  \"human-readable-id-taken\"}. A \"new\" equal to what is stored is not a write.

  400 for an unknown property, a missing or empty \"changes\", an entry without
  both \"old\" and \"new\" keys, and a \"new\" of the wrong type; 404 for an item
  that does not exist.

  A title change is what the app's own save does: the previous version goes to
  the history, stamped with the source it had; the item is then stamped \"api\".
  Titles shown in the collection items of the item's children follow.

  Gated by recording mode, with no door: shut, the request is logged and
  answered 200 with the item as it stands, and nothing is compared or written."
  [db id req]
  (try (let [id (Integer/parseInt id)
             body (parse-json-body req)
             changes (when (map? body) (:changes body))]
         (if-let [problem (patch-changes-problem changes)]
           (json-response 400 {:error problem})
           (let [item (datastore/get-item db {:id id})]
             (if (nil? (:id item))
               (json-response 404 {:error "Item not found"})
               (mw/log-and-guard
                 "patch-item"
                 {:id id :title (:title item) :changes changes}
                 (json-response (item->api item))
                 (fn [] (patch-item-impl db id changes)))))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))))

(defn- force-show-badge!
  "link-item-to-another-item! preserves :show-badge? on existing entries; this
  patches :data.contexts.<target>.show-badge? to the requested value so the
  upsert reflects the caller's intent."
  [db source-id target-id show-badge?]
  (let [item (datastore/get-item db {:id source-id})
        data (assoc-in (or (:data item) {})
                       [:contexts target-id :show-badge?] show-badge?)]
    (db/execute! db
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
        plan (et.rz.hub.repository.deletion/plan-and-execute! db items dry-run? (:id context))]
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
  endpoints execute; while OFF they log intent and answer with a 2xx stub
  shaped like the endpoint's success, except where a door lets them through or a
  door refuses them with 409.
  Prefer the in-app shortcut Option+Shift+W for day-to-day toggling."
  []
  (let [now (mw/toggle!)]
    (log/info {:recording now} (str "RECORDING MODE " (if now "ON" "OFF")))
    (json-response {:recording now})))

(ns repository.deletion
  (:require [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [cheshire.core :as json]
            [cambium.core :as log]
            [config :as config]
            [datastore.dialect :as dialect]
            [repository.homefolder :as home]
            [et.vp.ds :as datastore]))

(defn- folder [k] (get-in config/config [:folders k]))

(defn- get-files-count
  [db file]
  (if file (count (datastore/get-items-by-path db "data->'resource-links'->>'file'" file)) 0))

(defn- file-path [folder-key file] (str (io/file (folder folder-key) file)))

(defn- found-files
  [file]
  (if-not file
    []
    (filter (fn [folder-key] (.exists (io/file (file-path folder-key file))))
      [:images :audio :docs])))

(defn- delete-file
  [found-files file]
  (when file
    (if (= (count found-files) 0)
      (log/warn (str "No file found to delete."))
      (let [file-path (file-path (first found-files) file)]
        (log/info (str "Will delete file " file-path))
        (io/delete-file (io/file file-path))))))

(defn- delete-preview-images
  [id]
  (let [preview (folder :preview-images)
        highres-path (str (io/file preview (str id ".png")))
        lowres-path (str (io/file preview "Lowres" (str id ".png")))]
    (when (.exists (io/file highres-path))
      (log/info (str "Will remove " highres-path))
      (.delete (io/file highres-path)))
    (when (.exists (io/file lowres-path))
      (log/info (str "Will remove " lowres-path))
      (.delete (io/file lowres-path)))))

(defn- media-folder-missing
  "When the item's file maps to a deletable media folder (:images/:audio/:docs)
   that does not currently exist -- most likely an unmounted/offline drive --
   we must not delete: the file may still be on disk we simply can't see, and
   dropping the DB row would orphan it. Returns the folder-key when it blocks
   deletion, else nil. Scoped to the folders deletion actually touches (see
   found-files); video disk-deletion is already a no-op, so it is unaffected."
  [file]
  (when file
    (let [k (home/folder-key-for file)]
      (when (and (#{:images :audio :docs} k) (not (home/folder-exists? k)))
        k))))

(defn- file-safety-skip
  "Returns a {:status :skipped :reason …} map if it would be unsafe to delete
   this item's file: its media folder is missing (offline drive), the file is
   referenced by multiple items, or it is found in multiple locations on disk.
   Returns nil when safe to delete."
  [db item]
  (let [file (get-in item [:data :resource-links :file])
        files-count (get-files-count db file)
        found (found-files file)]
    (cond
      (media-folder-missing file)
        {:status :skipped :reason :media-folder-missing}
      (> files-count 1)
        {:status :skipped :reason :multiple-file-references}
      (> (count found) 1)
        {:status :skipped :reason :multiple-files-found})))

(defn- delete-item-and-files!
  "Hard-delete a single item's row and any associated files. Assumes the caller
   has already cleared relations and confirmed file-safety; this is the
   side-effecting tail of a cascade plan."
  [db {:keys [id] {{:keys [file]} :resource-links} :data}]
  (log/info (str "Deleting item id=" id (when file (str " file=" file))))
  (let [found (found-files file)]
    (delete-file found file)
    (datastore/delete-item db {:id id})
    (delete-preview-images id)))

;; -- cascade planner --------------------------------------------------------

(defn- relations-touching
  "All relation rows where owner_id or target_id is in `ids`."
  [db ids]
  (when (seq ids)
    (jdbc/execute! db
                   (sql/format {:select [:owner_id :target_id]
                                :from [:relations]
                                :where [:or
                                        [:in :owner_id (vec ids)]
                                        [:in :target_id (vec ids)]]}))))

(defn- count-by
  "Aggregate count of relations grouped by `col` (one of :owner_id, :target_id),
   restricted to the given ids. Returns {id → count}. Empty input → {}."
  [db col ids]
  (if-not (seq ids)
    {}
    (let [k-id (keyword "relations" (name col))
          k-c  :c]
      (->> (jdbc/execute! db
                          (sql/format {:select [col [[:count :*] :c]]
                                       :from [:relations]
                                       :where [:in col (vec ids)]
                                       :group-by [col]}))
           (map (fn [r] [(k-id r) (k-c r)]))
           (into {})))))

(defn- fetch-items-by-ids
  [db ids]
  (if-not (seq ids)
    []
    (let [rows (jdbc/execute! db
                              (sql/format {:select [:id :title :is_context :data]
                                           :from [:items]
                                           :where [:in :id (vec ids)]}))]
      (map (fn [r]
             {:id (:items/id r)
              :title (:items/title r)
              :is_context (let [v (:items/is_context r)]
                            (cond (boolean? v) v
                                  (number? v) (not (zero? v))
                                  :else (boolean v)))
              :data (let [d (:items/data r)]
                      (when d (json/parse-string (dialect/parse-json-value d) true)))})
           rows))))

(defn- classify-neighbor
  "Given a neighbor item and counts before/after the planned unlink, decide
   whether the item survives (keep-reasons non-empty) or gets cascade-deleted."
  [{:keys [is_context]} {:keys [inbound-after children-after]}]
  (let [reasons (cond-> []
                  is_context (conj :is-context-flag)
                  (pos? children-after) (conj :has-other-children)
                  (pos? inbound-after) (conj :has-other-inbound))]
    (if (seq reasons)
      {:disposition :unlink-only :keep-reasons reasons}
      {:disposition :cascade-delete})))

(defn plan
  "Pure analysis: given the primary items (already filter-narrowed) and the
   context the operation runs from, return the deletion plan as a map
   suitable for both preview and execution. The context itself is always
   omitted from the :unlinked bucket — it is by definition going to lose
   its containment relations to the primaries, so surfacing it as a kept
   neighbor is just noise.

   Output keys:
     :primary    — items in the user's selection; each tagged :status :deleted
                   (or :skipped + :reason if file-safety blocks)
     :cascade    — neighbors that become orphaned after unlinking and will
                   also be deleted
     :unlinked   — neighbors whose relations get unlinked but the items
                   survive, with :keep-reasons explaining why
     :relations-to-delete — relation rows that will be removed (used by
                            the executor; not surfaced in the API response)"
  [db primaries context-id]
  (let [primary-ids (set (map :id primaries))
        rels (relations-touching db primary-ids)
        ;; relations partitioned by which side touches a primary
        rel-by-neighbor (reduce (fn [acc {:relations/keys [owner_id target_id]}]
                                  (let [owner-primary? (contains? primary-ids owner_id)
                                        target-primary? (contains? primary-ids target_id)]
                                    (cond
                                      ;; both sides in primary set — relation just disappears
                                      (and owner-primary? target-primary?) acc
                                      ;; neighbor is on owner side; we unlink an inbound rel of neighbor
                                      target-primary?
                                        (update acc owner_id
                                                (fn [m]
                                                  (-> (or m
                                                          {:inbound-removed 0
                                                           :children-removed 0
                                                           :linked-primaries #{}})
                                                      (update :children-removed inc)
                                                      (update :linked-primaries conj target_id))))
                                      ;; neighbor is on target side; we unlink an outbound rel of neighbor
                                      owner-primary?
                                        (update acc target_id
                                                (fn [m]
                                                  (-> (or m
                                                          {:inbound-removed 0
                                                           :children-removed 0
                                                           :linked-primaries #{}})
                                                      (update :inbound-removed inc)
                                                      (update :linked-primaries conj owner_id)))))))
                                {}
                                rels)
        neighbor-ids (keys rel-by-neighbor)
        inbound-totals (count-by db :target_id neighbor-ids)
        children-totals (count-by db :owner_id neighbor-ids)
        neighbor-items (->> (fetch-items-by-ids db neighbor-ids)
                            (map (juxt :id identity))
                            (into {}))
        neighbor-rows
        (for [nid neighbor-ids
              :let [it (get neighbor-items nid)
                    {:keys [inbound-removed children-removed linked-primaries]}
                      (rel-by-neighbor nid)
                    after {:inbound-after (- (get inbound-totals nid 0) inbound-removed)
                           :children-after (- (get children-totals nid 0) children-removed)}
                    {:keys [disposition keep-reasons]} (classify-neighbor it after)]]
          {:id nid
           :title (:title it)
           :disposition disposition
           :keep-reasons keep-reasons
           :unlinked-from (vec linked-primaries)
           :data (:data it)})
        cascade-rows (filter #(= :cascade-delete (:disposition %)) neighbor-rows)
        ;; The parent context the operation runs from is always going to lose
        ;; its links to the primaries — that is the operation itself. Surfacing
        ;; it in :unlinked just adds confusion, so we drop it.
        unlink-rows  (->> neighbor-rows
                          (filter #(= :unlink-only (:disposition %)))
                          (remove #(= context-id (:id %))))
        primary-status
        (mapv (fn [{:keys [id title] :as it}]
                (if-let [skip (file-safety-skip db it)]
                  (assoc skip :id id :title title)
                  {:id id :title title :status :deleted}))
              primaries)
        cascade-status
        (mapv (fn [{:keys [id title data] :as row}]
                (if-let [skip (file-safety-skip db {:id id :data data})]
                  (assoc skip :id id :title title)
                  {:id id :title title :status :deleted}))
              cascade-rows)
        unlinked-status
        (mapv (fn [{:keys [id title keep-reasons unlinked-from]}]
                {:id id :title title
                 :keep-reasons (vec keep-reasons)
                 :unlinked-from unlinked-from})
              unlink-rows)]
    {:primary  primary-status
     :cascade  cascade-status
     :unlinked unlinked-status
     :relations-to-delete rels
     ;; ids of items the executor should actually hard-delete
     :delete-ids (->> (concat primary-status cascade-status)
                      (filter #(= :deleted (:status %)))
                      (map :id))
     ;; map from kept-neighbor-id → set of primary-ids whose links to drop from
     ;; the kept-neighbor's data.contexts JSON
     :unlink-edits (into {}
                         (map (fn [{:keys [id unlinked-from]}] [id (set unlinked-from)])
                              unlink-rows))}))

(defn- drop-context-keys
  "Remove the given primary ids from a kept-neighbor's data.contexts map.
   Keys are stored as strings in the JSON, so we match on both string and
   integer forms to be safe across history shapes."
  [data primary-ids]
  (update data :contexts
          (fn [ctx]
            (if (map? ctx)
              (reduce (fn [m pid]
                        (-> m (dissoc pid) (dissoc (str pid)) (dissoc (keyword (str pid)))))
                      ctx
                      primary-ids)
              ctx))))

(defn- write-unlink-edits!
  "For each kept neighbor, rewrite its items.data so the dropped primaries
   no longer appear in :contexts."
  [db unlink-edits]
  (doseq [[neighbor-id primary-ids] unlink-edits
          :when (seq primary-ids)]
    (let [row (jdbc/execute-one! db
                                 (sql/format {:select [:data]
                                              :from [:items]
                                              :where [:= :id [:inline neighbor-id]]}))
          raw (:items/data row)
          data (if raw (json/parse-string (dialect/parse-json-value raw) true) {})
          new-data (drop-context-keys data primary-ids)]
      (jdbc/execute! db
                     (sql/format {:update [:items]
                                  :set {:data [:inline (json/generate-string new-data)]}
                                  :where [:= :id [:inline neighbor-id]]})))))

(defn- delete-relations-touching!
  [db ids]
  (when (seq ids)
    (jdbc/execute! db
                   (sql/format {:delete-from [:relations]
                                :where [:or
                                        [:in :owner_id (vec ids)]
                                        [:in :target_id (vec ids)]]}))))

(defn execute!
  "Carry out a plan from `plan`. Wraps writes in a transaction. Caller is
   responsible for branching on dry-run? (we never enter execute! in dry-run)."
  [db plan-result primary-ids-for-relations]
  (jdbc/with-transaction [tx db]
    (write-unlink-edits! tx (:unlink-edits plan-result))
    (delete-relations-touching! tx primary-ids-for-relations)
    (doseq [id (:delete-ids plan-result)]
      (let [item (datastore/get-item tx {:id id})]
        (when (:id item)
          (delete-item-and-files! tx item))))))

;; -- public API for mutations.clj ------------------------------------------

(defn- public-shape
  "Strip executor-only keys from the plan; keywords → JSON-friendly strings."
  [{:keys [primary cascade unlinked]}]
  {:primary  (mapv (fn [r] (cond-> r
                             (:status r) (update :status name)
                             (:reason r) (update :reason name)))
                   primary)
   :cascade  (mapv (fn [r] (cond-> r
                             (:status r) (update :status name)
                             (:reason r) (update :reason name)))
                   cascade)
   :unlinked (mapv (fn [r] (update r :keep-reasons #(mapv name %)))
                   unlinked)})

(defn plan-and-execute!
  "Build a plan for the given primaries, optionally execute it, and return
   the API response shape. `dry-run?` true means we never touch the DB.
   `context-id` is the context the operation runs from."
  [db primaries dry-run? context-id]
  (let [plan-result (plan db primaries context-id)
        primary-ids (set (map :id primaries))]
    (when-not dry-run?
      (doseq [{:keys [id title]} (->> (concat (:primary plan-result) (:cascade plan-result))
                                      (filter #(= :media-folder-missing (:reason %))))]
        (log/warn (str "Cannot delete file for item id=" id " '" title
                       "' -- its media folder does not exist (item kept, skipped).")))
      (execute! db plan-result primary-ids))
    (assoc (public-shape plan-result) :dry-run dry-run?)))

;; -- legacy single-item delete (kept; no longer used by danger-mode) -------

(defn delete-item
  "Delete an item, with safety checks. Returns a status map:
   {:status :deleted}                   — done (or, in dry-run, would be done)
   {:status :skipped :reason :has-children}
   {:status :skipped :reason :multiple-file-references}
   {:status :skipped :reason :multiple-files-found}

   Retained as the canonical single-item path. The danger-mode bulk flow no
   longer calls this — see `plan-and-execute!`."
  ([db item] (delete-item db item false))
  ([db {:keys [id] :as item} dry-run?]
   (log/info (str (if dry-run? "[dry-run] " "")
                  "Prepare deleting item with id '" id "'"))
   (let [contained-items-count (datastore/get-contained-items-count db id)
         skip (file-safety-skip db item)]
     (cond (> contained-items-count 0)
             (do (log/warn "Doing nothing. Item to be deleted still contains items.")
                 {:status :skipped :reason :has-children})
           skip (do (log/warn (str "Doing nothing. Skipped deleting item id='" id
                                   "' -- " (name (:reason skip)) "."))
                    skip)
           :else (do (when-not dry-run?
                       (delete-item-and-files! db item))
                     {:status :deleted})))))

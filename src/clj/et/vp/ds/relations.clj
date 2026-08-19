(ns et.vp.ds.relations
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [cambium.core :as log]
            [cheshire.core :as json]
            [datastore.dialect :as dialect]
            [et.vp.ds.part-of :as part-of]
            [et.vp.ds.helpers :as helpers]))

(defn- get-title
  [container]
  (or (and (string? (:short_title container)) (not-empty (:short_title container)))
      (:title container)))

(defn ->part-of-sort-idx
  "part_of_sort_idx the way the column wants it: an integer, -1 when unset. The
   edit modal parses what was typed into that field and sends the result, which
   is NaN when it was not a number -- a roman numeral included: this index is a
   plain integer, and does not share the convention items.sort_idx has."
  [v]
  (cond (integer? v) v
        (number? v) (if (Double/isNaN (double v)) -1 (long v))
        (string? v) (try (Long/parseLong (clojure.string/trim v)) (catch Exception _ -1))
        :else -1))

(defn- standing-of-row
  "Everything a mirror entry says about a relation that is not the title, read
   off the row it is stored in. A missing row reads as the column defaults --
   not-part-of, unplaced, badge shown."
  [db whole-id part-id]
  (let [r (jdbc/execute-one! db
                             (sql/format {:select [:is_part_of :part_of_sort_idx :show_badge]
                                          :from [:relations]
                                          :where [:and [:= :owner_id [:inline whole-id]]
                                                  [:= :target_id [:inline part-id]]]}))]
    {:is-part-of? (boolean (helpers/int->bool (:relations/is_part_of r)))
     :part-of-sort-idx (->part-of-sort-idx (:relations/part_of_sort_idx r))
     ;; nil is the column's default too (show_badge DEFAULT 1), and the mirror
     ;; has no way to say "unknown": get-aggregated-contexts reads a nil here as
     ;; false and drops the context out of the item's badges.
     :show-badge? (if (nil? (:relations/show_badge r))
                    true
                    (boolean (helpers/int->bool (:relations/show_badge r))))}))

(defn set-collection-titles-of-new-item
  [db item-id]
  (let [data (:items/data (jdbc/execute-one! db
                                             (sql/format {:select [:data]
                                                          :from [:items]
                                                          :where [:= :id [:inline item-id]]})
                                             {:return-keys true}))
        data (cond (nil? data) {}
                   :else (json/parse-string (dialect/parse-json-value data)))
        data (if (get data "contexts") data (assoc data "contexts" {}))
        contexts (dissoc (into {}
                               ;; The part-of columns are read back off the rows
                               ;; rather than assumed false: this builds the
                               ;; mirror from scratch out of the table, and a
                               ;; mirror that understates the table would lose
                               ;; the flag on the next save, which rebuilds the
                               ;; table out of the mirror.
                               (map (fn [{:items/keys [id title short_title is_context]
                                          :relations/keys [is_part_of part_of_sort_idx]}]
                                      [id
                                       {:title (if (seq short_title) short_title title)
                                        :show-badge? true
                                        :is-context? (helpers/int->bool is_context)
                                        :is-part-of? (helpers/int->bool is_part_of)
                                        :part-of-sort-idx (->part-of-sort-idx part_of_sort_idx)}])
                                 (jdbc/execute!
                                   db
                                   (sql/format {:select [:items.id :title :short_title :is_context
                                                         :relations.is_part_of
                                                         :relations.part_of_sort_idx]
                                                :from [:relations]
                                                :join [:items [:= :relations.owner_id :items.id]]
                                                :where [:= :relations.target_id [:inline item-id]]})
                                   {:return-keys true})))
                   item-id)]
    (log/info (str "item-id: " item-id ". contexts: " contexts "."))
    (jdbc/execute-one!
      db
      (sql/format {:update [:items]
                   :where [:= :id [:inline item-id]]
                   :set {:data [:inline (json/generate-string (assoc data "contexts" contexts))]}})
      {:return-keys true})))

(defn update-collection-title-in-collection-items
  "Standard use case is that you know item-id references id via contexts. That id has a new title, so we update it.
   @param constraints a list of ids; when set, the contexts of the item with item-id will be reduced to the ones present in that list
     so the use case is not to set the title in an item's context (with a given id), but to remove contexts
   @param is-part-of?/part-of-sort-idx when supplied, the part-of fields of that one entry are set to them.
     Callers that write the relation row itself have to supply them, or the mirror keeps saying what the
     row said before the write."
  [db item-id id
   {:keys [short_title title new-contexts show-badge? remove-from-container? is-context?
           is-part-of? part-of-sort-idx]}]
  (let [data (:items/data (jdbc/execute-one! db
                                             (sql/format {:select [:data]
                                                          :from [:items]
                                                          :where [:= :id [:inline item-id]]})
                                             {:return-keys true}))
        data (cond (nil? data) {}
                   :else (json/parse-string (dialect/parse-json-value data)))
        data (if (get data "contexts") data (assoc data "contexts" {}))
        data (update data
                     "contexts"
                     (fn [contexts]
                       (cond remove-from-container? (dissoc contexts (str id))
                             (map? new-contexts) new-contexts
                             :else (if (map? (get contexts (str id)))
                                     (-> contexts
                                         (assoc-in [(str id) "title"]
                                                   (if (seq short_title) short_title title))
                                         (cond-> (not (nil? is-context?))
                                                   (assoc-in [(str id) "is-context?"] is-context?))
                                         (cond-> (some? is-part-of?)
                                                   (assoc-in [(str id) "is-part-of?"]
                                                             (boolean is-part-of?)))
                                         (cond-> (some? part-of-sort-idx)
                                                   (assoc-in [(str id) "part-of-sort-idx"]
                                                             (->part-of-sort-idx
                                                               part-of-sort-idx))))
                                     ;; No entry to patch: this rebuilds one the
                                     ;; mirror had lost. Every field the caller
                                     ;; did not supply comes off the relation
                                     ;; row rather than being assumed away -- a
                                     ;; rebuilt entry that understates the row
                                     ;; is what the next save writes back, since
                                     ;; that rebuilds the table out of the
                                     ;; mirror.
                                     (let [row (standing-of-row db id item-id)]
                                       (assoc contexts
                                         (str id) (cond-> {:show-badge?
                                                             (if (some? show-badge?)
                                                               show-badge?
                                                               (:show-badge? row))
                                                           :title (if (seq short_title)
                                                                    short_title
                                                                    title)
                                                           :is-part-of?
                                                             (if (some? is-part-of?)
                                                               (boolean is-part-of?)
                                                               (:is-part-of? row))
                                                           :part-of-sort-idx
                                                             (if (some? part-of-sort-idx)
                                                               (->part-of-sort-idx part-of-sort-idx)
                                                               (:part-of-sort-idx row))}
                                                    (not (nil? is-context?))
                                                      (assoc :is-context? is-context?))))))))]
    (jdbc/execute-one! db
                       (sql/format {:update [:items]
                                    :where [:= :id [:inline item-id]]
                                    :set {:data [:inline (json/generate-string data)]}})
                       {:return-keys true})))

(defn update-collection-title-in-collection-items-for-children
  [db id title short_title]
  (let [item-ids (doall (map :relations/target_id
                          (jdbc/execute! db
                                         (sql/format {:select [:target_id]
                                                      :from [:relations]
                                                      :where [:= :owner_id [:inline id]]})
                                         {:return-keys true})))]
    (doall (for [item-id item-ids]
             (update-collection-title-in-collection-items db
                                                          item-id
                                                          id
                                                          {:short_title short_title
                                                           :title title})))))

(defn- normalize-part-of
  "Fill in the part-of fields of every entry of a containers map, so that whoever
   reads the map back -- the table writer or the mirror writer -- reads the same
   thing, and reads something the column can hold."
  [containers]
  (into {}
        (map (fn [[id container]]
               [id (assoc container
                     :is-part-of? (boolean (:is-part-of? container))
                     :part-of-sort-idx (->part-of-sort-idx (:part-of-sort-idx container)))]))
        containers))

(defn- present? [s] (and s (not (clojure.string/blank? s))))

(defn- save-relation-revision!
  "Archive the text an edge is carrying, before something replaces it.

   `et.vp.ds/save-revision-to-history!` for a relation, and the same mechanism
   edge for edge: the text about to be overwritten is copied under the next
   version number, stamped with the source that WROTE it rather than the one
   about to write, and a blank text is not archived at all -- there is nothing in
   it to recover, and a run of empty versions would be a version bar with nothing
   to step through.

   `tombstone?` says the edge itself is going away rather than its text being
   replaced, and it changes both of those. The row is written even when the text
   is blank -- what is being recorded is that the edge was cut, and an edge that
   carried nothing was still an edge -- and the row is marked, so a reader can
   tell the version that was superseded by a deletion from the versions that were
   superseded by a later text. See the note over the table in schema-sqlite.sql.

   Keyed on the two items and not on relations.id, because the row's id does not
   survive a save of either item's edit modal -- see set-containers-of-item!
   below, and the note over the table in schema-sqlite.sql."
  ([db item-id container-id text source]
   (save-relation-revision! db item-id container-id text source false))
  ([db item-id container-id text source tombstone?]
   (when (or tombstone? (present? text))
     (let [next-version
             (inc (:max_version
                    (jdbc/execute-one!
                      db
                      (sql/format {:select [[[:coalesce [:max :version] 0] :max_version]]
                                   :from [:relation_history]
                                   :where [:and [:= :owner_id [:inline container-id]]
                                           [:= :target_id [:inline item-id]]]}))))]
       (jdbc/execute-one! db
                          (sql/format {:insert-into [:relation_history]
                                       :values [{:owner_id [:inline container-id]
                                                 :target_id [:inline item-id]
                                                 :text [:inline text]
                                                 :version [:inline next-version]
                                                 :source [:inline source]
                                                 :tombstone [:inline (if tombstone? 1 0)]}]}))))
   nil))

(defn- edge-rows
  "The rows a tombstoning is about to lose, whichever way it is selecting them."
  [db where]
  (jdbc/execute! db
                 (sql/format {:select [:owner_id :target_id :description :description_source]
                              :from [:relations]
                              :where where})))

(defn- tombstone-rows!
  "Archive each of `rows` as the last version of its edge, marked as the cut.

   Every row, and not only the ones carrying text. An edge that was never written
   on was still an edge, and the version this leaves is a record that it was here
   and is not any more -- which is the whole of what a delete leaves behind, and
   the one thing a reader cannot work out from a table it is missing from."
  [db rows]
  (doseq [{:relations/keys [owner_id target_id description description_source]} rows]
    (save-relation-revision! db target_id owner_id description description_source true)))

(defn tombstone-inbound-relations!
  "Tombstone every edge that points AT `item-id`, for a delete of the item that
   is about to take those rows with it (et.vp.ds/delete-item).

   Inbound only, because that is the set that delete deletes. The bulk path
   clears both directions and tombstones them itself, before this ever runs --
   see tombstone-relations-touching!."
  [db item-id]
  (tombstone-rows! db (edge-rows db [:= :target_id [:inline item-id]])))

(defn- tombstone-dropped-inbound-relations!
  "Tombstone the inbound edges of `item-id` that `keep-ids` does not name: the
   ones a rewrite of the item's containers is about to drop.

   The filtering is here and not in the WHERE because `keep-ids` is routinely
   empty -- an item can be taken out of its last container by a delete -- and
   `[:not-in :owner_id []]` is not SQL."
  [db item-id keep-ids]
  (let [keep? (set keep-ids)]
    (tombstone-rows! db
                     (remove (fn [row] (contains? keep? (:relations/owner_id row)))
                             (edge-rows db [:= :target_id [:inline item-id]])))))

(defn tombstone-relations-touching!
  "Tombstone every edge with one end in `ids`, in either direction, for a bulk
   delete about to remove those rows (repository.deletion/execute!).

   Both directions, unlike the single-item path: a bulk delete can take a
   container and the things in it in one gesture, so an edge can lose the end it
   runs from as easily as the end it runs to, and the text hangs on the edge
   either way."
  [db ids]
  (when (seq ids)
    (tombstone-rows! db (edge-rows db [:or [:in :owner_id (vec ids)]
                                       [:in :target_id (vec ids)]]))))

(defn- texts-of-inbound-rows
  "The body text of each of an item's inbound relations and the source that wrote
   it, as {owner-id {:description text :source source}}.

   Read before set-containers-of-item! deletes those rows, and written back on
   the inserts that replace them. The description is the one thing a relation
   carries that the `contexts` mirror does not, deliberately -- it is loaded on
   hover and nowhere else, so it never travels with a list row and the client
   cannot hand it back the way it hands back the annotation. Without this, every
   save from the edit modal would rewrite the rows from a map that has never
   heard of it, and the text would be gone.

   The source travels with the text, and there is more at stake in it than in the
   text itself. A text that survived the rewrite with its source dropped would
   come back reading as nobody's -- `provenance/source-of` takes an empty column
   for the owner's own hand -- so an agent's paragraph would be handed back to him
   as his, in an answer that looks entirely well-formed. Carried and not
   re-stamped: rewriting the rows is not writing the text."
  [db item-id]
  (into {}
        (keep (fn [{:relations/keys [owner_id description description_source]}]
                (when description
                  [owner_id {:description description :source description_source}])))
        (jdbc/execute! db
                       (sql/format {:select [:owner_id :description :description_source]
                                    :from [:relations]
                                    :where [:= :target_id [:inline item-id]]}))))

(defn- set-containers-of-item!
  "Rewrite an item's inbound relations from `containers`. Every caller must run
   this inside a transaction that also covers the mirror write -- see
   set-the-containers-of-item! for why."
  [db item containers]
  (log/info (str "datastore.relations/set-containers-of-item! " (:id item)
                 "." (:title item)
                 "..." containers))
  ;; Every relation row is written here, so this is where acyclicity is kept --
  ;; before the delete, so a refused write leaves the relations exactly as they
  ;; were rather than half rewritten. It throws; see et.vp.ds.part-of.
  (part-of/check-acyclic! db
                          (:id item)
                          (keep (fn [[container-id {:keys [is-part-of?]}]]
                                  (when is-part-of? container-id))
                                containers))
  (let [texts (texts-of-inbound-rows db (:id item))]
    ;; An edge the new map does not mention is being taken away, and the delete
    ;; below takes its text with it. Tombstoned first, so it is still recoverable
    ;; afterwards: this is the one write in the system that can destroy a
    ;; relation's text without anyone having typed over it, and a field a delete
    ;; can carry off unrecorded is not a versioned field. The edge that comes back
    ;; if it is ever re-linked then starts out blank on top of one history with the
    ;; cut marked in the middle of it, which is what happened.
    (tombstone-dropped-inbound-relations! db (:id item) (keys containers))
    (jdbc/execute! db
                   (sql/format {:delete-from [:relations]
                                :where [:= :target_id [:inline (:id item)]]}))
    (doall
      (for [[container-id
             {:keys [show-badge? annotation is-part-of? part-of-sort-idx] :as container}]
              containers]
        (let [{:keys [description source]} (get texts container-id)]
          (jdbc/execute! db
                         (sql/format {:insert-into [:relations]
                                      :columns [:target_id :owner_id :annotation :description
                                                :description_source :show_badge :is_part_of
                                                :part_of_sort_idx]
                                      :values [[[:inline (:id item)] [:inline container-id]
                                                [:inline annotation]
                                                ;; A caller that carries the key
                                                ;; has the text in hand and wins.
                                                ;; The source stays the row's
                                                ;; either way: nothing here knows
                                                ;; who that caller is, and a
                                                ;; rewrite is not an edit.
                                                [:inline (if (contains? container :description)
                                                           (:description container)
                                                           description)]
                                                [:inline source]
                                                [:inline show-badge?]
                                                [:inline (boolean is-part-of?)]
                                                [:inline (->part-of-sort-idx
                                                           part-of-sort-idx)]]]})))))))

(defn set-the-containers-of-item!
  "@param containers - map {:container-id {:annotation \"annotation\"
                                           :show-badge? true|false
                                           :is-part-of? true|false
                                           :part-of-sort-idx int}}

   The acyclicity check, the delete, the inserts and the mirror write are one
   transaction. Both halves of that matter:

   - The check only means anything if nothing can write a part-of edge between
     it and the rows it authorises. Two clients each making the other's item a
     part -- an agent on /api while the human saves the modal -- would otherwise
     both read an acyclic graph and both be accepted, composing a cycle out of
     two writes that were individually legal. SQLite serialises write
     transactions, so the second one to reach for the write lock is refused and
     rolls back rather than landing on a graph it never checked.
   - The rows and the mirror describe the same relations, and a failure between
     them leaves the two disagreeing -- in the direction that loses data, since
     the next save rebuilds the table out of the mirror."
  [db item containers is_context]
  ;; One normalised map feeds both representations: the same values go into the
  ;; relation rows and into the mirror, so the two cannot come out of this
  ;; disagreeing. It also keeps a NaN sort index -- what the modal sends when the
  ;; user typed something that is not a number -- out of the JSON, where it would
  ;; not survive the round trip.
  (let [containers (normalize-part-of containers)]
    (if (or is_context (seq (keys containers)))
      (jdbc/with-transaction [tx db]
        (set-containers-of-item! tx item containers)
        (update-collection-title-in-collection-items
          tx
          (:id item)
          nil
          {:short_title nil :title nil :new-contexts containers}))
      (log/info {:is_context is_context :item (select-keys item [:id :title])}
                "cant take out the remaining context if item is not a context"))))

(defn link-item-to-another-item!
  "@param part-of - optional {:is-part-of? true|false :part-of-sort-idx int}. Left out,
     an existing relation keeps the part-of standing it had; a new one starts out not
     part-of. The entry for another-item is rebuilt from scratch here, so anything the
     old one carried and this one doesn't is dropped -- and set-containers-of-item!
     writes the table from exactly this map, so a dropped field is a cleared column."
  ([db item another-item show-badge?] (link-item-to-another-item! db item another-item show-badge? nil))
  ([db item another-item show-badge? part-of]
   (let [previous (get (:contexts (:data item)) (:id another-item))
         is-part-of? (boolean (if (contains? part-of :is-part-of?)
                                (:is-part-of? part-of)
                                (:is-part-of? previous)))
         part-of-sort-idx (->part-of-sort-idx (if (contains? part-of :part-of-sort-idx)
                                                (:part-of-sort-idx part-of)
                                                (:part-of-sort-idx previous)))
         contexts (merge (:contexts (:data item))
                         {(:id another-item) {:title (get-title another-item)
                                              :show-badge? show-badge?
                                              :is-context? (helpers/int->bool (:is_context another-item))
                                              :is-part-of? is-part-of?
                                              :part-of-sort-idx part-of-sort-idx}})]
     (jdbc/with-transaction [tx db]
       (set-containers-of-item! tx item contexts)
       (update-collection-title-in-collection-items tx
                                                    (:id item)
                                                    (:id another-item)
                                                    {:short_title (:short_title another-item)
                                                     :title (:title another-item)
                                                     :show-badge? show-badge?
                                                     :is-context? (boolean (:is_context
                                                                             another-item))
                                                     :is-part-of? is-part-of?
                                                     :part-of-sort-idx part-of-sort-idx})))))

(defn- would-leave-no-containers?
  "Whether unlinking `item` from `another-item` would drop its last container.
   A context is allowed to stand on its own; anything else has to stay in at
   least one, or nothing lists it any more.

   The condition is the one `unlink-item-from-another-item!` has always branched
   on, moved here unchanged so that the refusal sentence below and the write can
   only ever be reading the same rule."
  [item another-item]
  (let [containers (dissoc (:contexts (:data item)) (:id another-item))]
    (not (or (seq (keys containers)) (:is_context item)))))

(defn last-container-refusal
  "The refusal sentence for an unlink that `unlink-item-from-another-item!` would
   decline, or nil when it would go through. Stated next to the rule, and off the
   same predicate the write itself branches on, so the sentence and the rule
   cannot drift apart -- the arrangement `part-of/cycle-refusal` already has with
   `check-acyclic!`.

   Both titles are named. The gesture is aimed at one edge out of however many
   the item has, and a refusal that does not say which one leaves the user
   guessing at what to do about it."
  [item another-item]
  (when (would-leave-no-containers? item another-item)
    (str "Refused: \"" (:title item) "\" is only in \"" (:title another-item)
         "\" — an item has to stay in at least one context.")))

(defn unlink-item-from-another-item!
  [db item another-item]
  (let [selected-item (update-in item [:data :contexts] #(dissoc % (:id another-item)))
        containers (:contexts (:data selected-item))]
    (log/info {:is_context (:is_context item) :containers containers}
              "unlink-item-from-another-item!")
    (if (would-leave-no-containers? item another-item)
      (do (log/info {:item (select-keys item [:id :title])
                     :container (select-keys item [:id :title])}
                    "can't unlink item from another item")
          false)
      (do (jdbc/with-transaction [tx db]
            (set-containers-of-item! tx selected-item containers)
            (update-collection-title-in-collection-items
              tx
              (:id selected-item)
              (:id another-item)
              {:short_title nil :title nil :remove-from-container? true}))
          true))))

(defn update-relation-annotation!
  [db item-id context-id annotation]
  (jdbc/execute-one! db
                     (sql/format {:update [:relations]
                                  :set {:annotation [:inline annotation]}
                                  :where [:and [:= :target_id [:inline item-id]]
                                          [:= :owner_id [:inline context-id]]]})))

(defn- container-entry
  "A mirror entry for `container-id` built from the container itself: what the
   entry says besides the standing of the relation. Only reached when the mirror
   has no entry to patch -- dev-seed writes relation rows with raw SQL and never
   the mirror, and the human's dev db is full of items in that shape."
  [db container-id]
  (let [{:items/keys [title short_title is_context]}
          (jdbc/execute-one! db
                             (sql/format {:select [:title :short_title :is_context]
                                          :from [:items]
                                          :where [:= :id [:inline container-id]]}))]
    {"title" (if (seq short_title) short_title title)
     "is-context?" (boolean (helpers/int->bool is_context))}))

(defn- set-mirror-standing!
  "Write the standing of one relation into the part's contexts mirror, read back
   off the row rather than off what the caller asked for: the row has already
   been written when this runs, so the two representations cannot come out of
   here disagreeing whatever the caller sent."
  [db item-id container-id]
  (let [row (standing-of-row db container-id item-id)
        data (:items/data (jdbc/execute-one! db
                                             (sql/format {:select [:data]
                                                          :from [:items]
                                                          :where [:= :id [:inline item-id]]})
                                             {:return-keys true}))
        data (if (nil? data) {} (json/parse-string (dialect/parse-json-value data)))
        data (if (get data "contexts") data (assoc data "contexts" {}))
        entry (get-in data ["contexts" (str container-id)])
        entry (merge (if (map? entry) entry (container-entry db container-id))
                     {"show-badge?" (:show-badge? row)
                      "is-part-of?" (:is-part-of? row)
                      "part-of-sort-idx" (:part-of-sort-idx row)})]
    (jdbc/execute-one!
      db
      (sql/format {:update [:items]
                   :where [:= :id [:inline item-id]]
                   :set {:data [:inline (json/generate-string (assoc-in data
                                                                        ["contexts"
                                                                         (str container-id)]
                                                                        entry))]}})
      {:return-keys true})))

(defn update-relation-standing!
  "Set the badge and the part-of standing of the one relation that runs from
   `container-id` (the whole) to `item-id` (the part), and leave every other
   relation of either alone.

   @param standing - a map, and only the keys it actually carries are written:
     {:show-badge? true|false :is-part-of? true|false :part-of-sort-idx int}

   The edit modal rewrites all of an item's relations at once, from the map the
   client hands back (set-the-containers-of-item!). This is the other gesture:
   one edge, edited where it is shown, out in the list. Rewriting the lot from
   here would mean rebuilding every other edge out of the mirror -- including
   the annotations, which the mirror only carries because get-item glues them on
   from a GROUP_CONCAT -- to change one column of one row. So the row is written
   in place instead.

   The row goes first and the mirror is then read back off it, and both are one
   transaction with the acyclicity check, for the reason set-the-containers-of-item!
   states: the check only means anything if nothing can write a part-of edge
   between it and the row it authorises.

   Throws the acyclicity refusal (et.vp.ds.part-of/check-acyclic!) when ticking
   `part of` here would make a thing part of itself. Returns false, having
   written nothing, when there is no such relation to edit."
  [db item-id container-id {:keys [show-badge? is-part-of? part-of-sort-idx] :as standing}]
  (let [set-map (cond-> {}
                  (contains? standing :show-badge?) (assoc :show_badge
                                                      [:inline (boolean show-badge?)])
                  (contains? standing :is-part-of?) (assoc :is_part_of
                                                      [:inline (boolean is-part-of?)])
                  (contains? standing :part-of-sort-idx)
                    (assoc :part_of_sort_idx [:inline (->part-of-sort-idx part-of-sort-idx)]))]
    (if (empty? set-map)
      false
      (jdbc/with-transaction [tx db]
        (if-not (jdbc/execute-one! tx
                                   (sql/format {:select [:id]
                                                :from [:relations]
                                                :where [:and [:= :owner_id [:inline container-id]]
                                                        [:= :target_id [:inline item-id]]]}))
          (do (log/info {:item-id item-id :container-id container-id}
                        "no such relation to set the standing of")
              false)
          (do (when is-part-of? (part-of/check-acyclic! tx item-id [container-id]))
              (jdbc/execute-one! tx
                                 (sql/format {:update [:relations]
                                              :set set-map
                                              :where [:and [:= :target_id [:inline item-id]]
                                                      [:= :owner_id [:inline container-id]]]}))
              (set-mirror-standing! tx item-id container-id)
              true))))))

(defn relation-description
  "The body text of one relation, or nil.

   Its own read, and nothing else reads it. Every other field of a relation
   reaches the client on the back of a list row -- the annotation is projected by
   the search queries, the badge and the part-of standing come off the `contexts`
   mirror -- and a body of text on every row of every list is what this must not
   become. So it is not projected, not mirrored, and asked for one edge at a time,
   when a pointer comes to rest on it."
  [db item-id container-id]
  (:relations/description
    (jdbc/execute-one! db
                       (sql/format {:select [:description]
                                    :from [:relations]
                                    :where [:and [:= :target_id [:inline item-id]]
                                            [:= :owner_id [:inline container-id]]]}))))

(defn- relation-text-row
  "The two columns a relation's text is held in -- the text and who wrote it --
   or nil when there is no such edge."
  [db item-id container-id]
  (jdbc/execute-one! db
                     (sql/format {:select [:description :description_source]
                                  :from [:relations]
                                  :where [:and [:= :target_id [:inline item-id]]
                                          [:= :owner_id [:inline container-id]]]})))

(defn update-relation-description!
  "Replace the body text of one relation, keeping the text it replaces.

   `et.vp.ds/update-context-description` for a relation, with one deliberate
   difference, and it is the reason anything is compared here at all. There a save
   IS an edit: the description modal exists to write a description and nothing
   else, so every save of it earns a version even when the text came back
   identical. This save is not. The relation modal writes the body alongside the
   badge, the part-of tick and two annotations, and sends it whenever any of those
   is touched (ui.modals.annotation-edit/get-values), so archiving on every save
   would fill the history with versions nobody wrote -- ticking a badge four times
   would be four versions of an unchanged text. An unchanged text is therefore not
   written either, which is also what keeps the source column honest: re-stamping
   it for a save that wrote nothing would hand the text to whoever saved last.

   `source` is who is writing, in the vocabulary `provenance/ours` reads. It
   defaults to \"app\" -- the person at the web UI, which is the only writer this
   field has today.

   Read, archive and write are one transaction, the arrangement
   update-relation-standing! above has and for the same kind of reason: two saves
   landing together would otherwise both read the same old text and both claim the
   same version number, and one of them would be refused by the primary key after
   having already overwritten the row.

   Returns true when it wrote and nil when it did not -- there being no such edge,
   or the text being the one already there."
  ([db item-id container-id description]
   (update-relation-description! db item-id container-id description "app"))
  ([db item-id container-id description source]
   (jdbc/with-transaction [tx db]
     (when-let [row (relation-text-row tx item-id container-id)]
       (when (not= (:relations/description row) description)
         (save-relation-revision! tx
                                  item-id
                                  container-id
                                  (:relations/description row)
                                  (:relations/description_source row))
         (jdbc/execute-one! tx
                            (sql/format {:update [:relations]
                                         :set {:description [:inline description]
                                               :description_source [:inline source]}
                                         :where [:and [:= :target_id [:inline item-id]]
                                                 [:= :owner_id [:inline container-id]]]}))
         true)))))

(defn get-relation-description-history
  "Every version of one relation's text, newest first, and how many there are --
   the shape `et.vp.ds/get-description-history` answers with for an item.

   Two differences from that one, both deliberate.

   **The current version is always at the head, blank or not**, as long as the
   edge is still there. For an item the current row joins the list only when it
   has something in it, and it can afford to: the list is read in the detail view
   and the description is edited somewhere else. Here the one surface that shows
   this list is the one that EDITS the text, so the head of the list has to be the
   text that is standing. A cleared description that dropped out would leave the
   modal's editor showing the newest version that was not blank, and the next save
   would put a text the user had deleted back onto the edge.

   **The current version carries no timestamp.** A relation has no updated_at of
   its own, and there is nothing to borrow one from: the items at either end are
   touched by every edit to themselves, so dating the edge's text by one of them
   would be a date about something else. The archived rows do carry `created_at`,
   which is when each was superseded, exactly as an item's do.

   An edge that is gone still answers with its archive, and with no current
   version at the head. Unlinking takes the row and its text away, having archived
   the text on the way out (set-containers-of-item!), and what is left is the
   history -- which is the honest answer to what this relation's text was. The
   version it went out on carries `:tombstone true`, and so does every earlier cut:
   an edge that was unlinked and linked again answers with one list, and the marks
   say where in it the edge was not there."
  [db item-id container-id]
  (let [row (relation-text-row db item-id container-id)
        archived (mapv (fn [r]
                         {:text (:relation_history/text r)
                          :version (:relation_history/version r)
                          :created_at (:relation_history/created_at r)
                          :source (:relation_history/source r)
                          ;; A boolean and not the column's 0/1: the client is
                          ;; cljs, where 0 is truthy, and a version bar that
                          ;; called every version a deletion is what that costs.
                          :tombstone (= 1 (:relation_history/tombstone r))})
                   (jdbc/execute! db
                                  (sql/format {:select [:text :version :created_at :source
                                                        :tombstone]
                                               :from [:relation_history]
                                               :where [:and [:= :owner_id [:inline container-id]]
                                                       [:= :target_id [:inline item-id]]]
                                               :order-by [[:version :desc]]})))
        versions (if row
                   (into [{:text (:relations/description row)
                           :version (inc (or (:version (first archived)) 0))
                           :created_at nil
                           :source (:relations/description_source row)
                           :tombstone false
                           :current true}]
                         archived)
                   archived)]
    {:versions versions :total (count versions)}))

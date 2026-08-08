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
  (jdbc/execute! db
                 (sql/format {:delete-from [:relations]
                              :where [:= :target_id [:inline (:id item)]]}))
  (doall (for [[container-id {:keys [show-badge? annotation is-part-of? part-of-sort-idx]}]
                 containers]
           (jdbc/execute! db
                          (sql/format {:insert-into [:relations]
                                       :columns [:target_id :owner_id :annotation :show_badge
                                                 :is_part_of :part_of_sort_idx]
                                       :values [[[:inline (:id item)] [:inline container-id]
                                                 [:inline annotation] [:inline show-badge?]
                                                 [:inline (boolean is-part-of?)]
                                                 [:inline (->part-of-sort-idx
                                                            part-of-sort-idx)]]]})))))

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

(defn unlink-item-from-another-item!
  [db item another-item]
  (let [selected-item (update-in item [:data :contexts] #(dissoc % (:id another-item)))
        containers (:contexts (:data selected-item))]
    (log/info {:is_context (:is_context item) :containers containers}
              "unlink-item-from-another-item!")
    (if-not (or (seq (keys containers)) (:is_context item))
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
(ns repository
  (:require [clojure.string :as str]
            [et.vp.ds :as datastore]
            [et.vp.ds.search :as search]
            [et.vp.ds.relations :as datastore.relations]
            [et.vp.ds.part-of :as part-of]
            [cambium.core :as log]
            [provenance :as provenance]
            [replica :as replica]
            [repository.insertion :as insertion]
            [repository.deletion :as deletion]
            [semsearch.query :as semsearch]
            [opener]))

(defn search-aggregated-contexts
  [db {{{:keys [highlighted-secondary-contexts]} :data} :selected-item :as opts}]
  (let [items (search/search-related-items db "" (:id (:selected-item opts)) {} {})]
    (search/get-aggregated-contexts db items highlighted-secondary-contexts)))

(defn- simplify-params
  [{:keys [selected-item] :as opts}]
  (let [selected-item-id (:id selected-item)
        opts (-> opts
                 (merge (select-keys
                          (-> selected-item
                              :data
                              :views
                              :current)
                          [:secondary-contexts-inverted :secondary-contexts-unassigned-selected
                           :selected-secondary-contexts :search-mode :description-filter]))
                 (dissoc :selected-item)
                 (assoc :selected-item-id (:id selected-item)))]
    [selected-item-id opts]))

(def limit 100)

(defn- search'
  "Prefer calling search-items or search-related-items"
  [db q selected-item-id {:keys [link-item selected-item] :as opts}]
  (log/info (str "search:" selected-item-id " link-item:" link-item))
  (when selected-item
    (throw (IllegalArgumentException. "'selected-item' not expected as an argument here")))
  (if selected-item-id
    (if link-item
      (search/search-items db
                           q
                           (assoc opts
                             :all-items? true
                             :selected-item-id selected-item-id)
                           {:limit limit})
      (search/search-related-items db q selected-item-id opts {}))
    (search/search-items db q (assoc opts :all-items? true) {:limit limit})))

(defn- search-items [db] (search/search-items db "" {:all-items? true} {:limit limit}))

(defn- search
  [db {:keys [q] :as opts}]
  (let [[selected-item-id opts] (simplify-params opts)] (search' db q selected-item-id opts)))

;; TODO this seems to replicate what's done in ds namespace (see update-contexts fn there)
(defn- update-contexts
  [item]
  (update-in
    item
    [:data :contexts]
    (fn [contexts]
      (into {} (map (fn [[k v]] [k (if (map? v) v {:title v :show-badge? true})]) contexts)))))

(defn- search-context-items
  [db q opts]
  (let [[selected-item-id opts] (simplify-params opts)
        global? (and (nil? selected-item-id) (not (:link-context opts)))
        opts (cond-> opts global? (assoc :exclude-hidden? true))]
    (map update-contexts (search/search-items db q opts {:limit limit}))))

(defn- search-related-items
  ([db q selected-item] (search-related-items db q selected-item {}))
  ([db q selected-item opts]
   (let [[selected-item-id opts] (simplify-params (assoc opts :selected-item selected-item))]
     (search/search-related-items db q selected-item-id opts {}))))

(defn- hierarchy-opts
  "Hierarchy mode is session state -- it is not stored on the context the way a
   view is, so it rides along on every request the SPA makes and has to be handed
   on wherever the item list is rebuilt from something other than the whole state
   map. The level being looked at rides along with it, for the same reason and by
   the same route."
  [state]
  (select-keys state [:hierarchy-mode? :hierarchy-level]))

(defn- hierarchy-bound
  "How deep the part-of edges below the selected context run -- the level past
   which the strip's stepper has nothing to offer.

   It travels back with the item list rather than being asked for once, because
   it is not a property of the session but of the tree: an edit to a part-of edge
   reshapes what the levels index, and the stepper would go on offering a step
   into an empty list until something else happened to refresh it. Only in
   hierarchy mode -- nothing else asks the question, and answering it walks the
   subgraph.

   Scoped to the context it was counted for, the way the level the SPA sends is
   (see et.vp.ds.search/level-asked-for). Same reason: a response that changes
   the selected context without rebuilding the list would otherwise leave the
   strip bounding one context's stepper by another context's depth.

   Counted with the same `q` the list beside it was built with. A bound counted
   over the unfiltered tree bounds a list that is filtered, which is how the
   stepper came to offer a step and answer it empty -- the one thing it is there
   to prevent. So `q` is a parameter here and not read off the state: the two
   have to be the same string, and the only way to be sure of that is for the
   caller that chose it to hand it over."
  ([db state] (hierarchy-bound db (:q state) state (:selected-item state)))
  ([db q state selected-item]
   (when (and (:hierarchy-mode? state) (:id selected-item))
     {:hierarchy-max-level {:context (:id selected-item)
                            :level (search/part-of-depth db (or q "") (:id selected-item))}})))

(defn- items-under
  "The item list under `selected-item`, and with it whatever hierarchy mode needs
   alongside the list to draw its strip. One `q` for both, so the strip can only
   ever be bounding the list it is standing over."
  ([db selected-item state] (items-under db "" selected-item state))
  ([db q selected-item state]
   (merge {:items (search-related-items db q selected-item (hierarchy-opts state))}
          (hierarchy-bound db q state selected-item))))

(defn- log-opts
  [{:keys [cmd q active-search] :as _opts}]
  (log/debug (str "list-resources - " (or cmd (str active-search "(" q ")")))))

(defn fetch-aggregated-contexts
  [{:keys [db]}]
  (fn [state]
    (when-not (:selected-item state)
      (throw (Exception. "fetch-aggregated-contexts called without selected-item")))
    (search-aggregated-contexts db state)))

(defn fetch-context
  [{:keys [db]}]
  (fn [old-state [arg fetch-as-item?]]
    (let [selected-item (datastore/get-item db arg)
          history (datastore/get-description-history db arg)
          descriptions (:versions history)
          opts {:selected-item selected-item}]
      (log/info (str "fetch-context as "
                     (if fetch-as-item? "item" "context")
                     " from ("
                     (:id (:old-selected-item old-state))
                     "):\""
                     (:title (:old-selected-context old-state))
                     "\" to ("
                     (:id selected-item)
                     "):\""
                     (:title selected-item)
                     "\""))
      (log/info (str "Fetched " (count descriptions) " description versions"))
      ;; Selecting something is a read for the user, but it touches the row's
      ;; ordering timestamps. On a read-only replica that touch is skipped so
      ;; navigation keeps working -- it is the one write on a query path, which
      ;; is why fetch-context is classified as a query in dispatch.
      (when-not (replica/read-only?)
        (if fetch-as-item?
          (datastore/reprioritize-item db arg)
          (datastore/reprioritize-context db arg)))
      (merge opts
             (items-under db selected-item old-state)
             {:selected-item selected-item
              :context-to-fetch nil
              :unassigned-secondary-contexts-selected? false
              :q nil
              :item-descriptions descriptions}))))

(defn deselect-context
  [{:keys [db]}]
  (fn [_opts]
    (log/info (str "deselect context"))
    {:items (search-items db) :contexts (search-context-items db "" {}) :selected-item nil :q nil}))

(defn the-future [arg] (future arg))

(defn- change-secondary-contexts-operation
  [db]
  (fn [opts]
    (log/info (str "repository/change-secondary-contexts-operation: " (:id (:selected-item opts))
                   "-" (:title (:selected-item opts))))
    (let [_context (the-future (datastore/update-item db (:selected-item opts)))]
      (items-under db (:q opts) (:selected-item opts) opts))))

(defn change-secondary-contexts-selection [{:keys [db]}] (change-secondary-contexts-operation db))

(defn change-secondary-contexts-unassigned-selected
  [{:keys [db]}]
  (change-secondary-contexts-operation db))

(defn change-secondary-contexts-inverted [{:keys [db]}] (change-secondary-contexts-operation db))

(defn change-description-filter [{:keys [db]}] (change-secondary-contexts-operation db))

(defn deselect-secondary-contexts
  [{:keys [db]}]
  (fn [opts]
    (let [context
            (datastore/update-item
              db
              (:selected-item
                (-> opts
                    (assoc-in [:selected-item :data :views :current :selected-secondary-contexts]
                              [])
                    (assoc-in [:selected-item :data :views :current :secondary-contexts-inverted]
                              false)
                    (assoc-in [:selected-item :data :views :current
                               :secondary-contexts-unassigned-selected]
                              false)
                    (assoc-in [:selected-item :data :views :current :search-mode] 0)
                    (assoc-in [:selected-item :data :views :current :notes-mode] false)
                    (assoc-in [:selected-item :data :views :current :description-filter] nil))))]
      (merge (items-under db (:q opts) context opts)
             {:contexts (search-context-items db "" {}) :selected-item context}))))

(defn store-current-view
  [{:keys [db]}]
  (fn [{:keys [selected-item]} item]
    (let [selected-item (datastore/store-current-view db selected-item item)]
      {:selected-item selected-item})))

(defn load-stored-context
  [{:keys [db]}]
  (fn [{:keys [selected-item] :as opts} idx]
    (let [selected-item (datastore/load-stored-context db selected-item idx)]
      (merge (items-under db (:q opts) selected-item opts) {:selected-item selected-item}))))

(defn remove-stored-context
  [{:keys [db]}]
  (fn [{:keys [selected-item]} idx]
    (let [selected-item (datastore/remove-stored-context db selected-item idx)]
      {:selected-item selected-item})))

(defn cycle-search-mode
  [{:keys [db]}]
  (fn [{:keys [selected-item] :as opts}]
    (let [selected-item (datastore/cycle-search-mode db selected-item)]
      (merge (items-under db (:q opts) selected-item opts) {:selected-item selected-item}))))

(defn delete-item
  [{:keys [db]}]
  (fn [opts item]
    (deletion/delete-item db item)
    (merge {:items (search db opts) :item-view? false} (hierarchy-bound db opts))))


(defn delete-context
  [{:keys [db]}]
  (fn [opts arg]
    (deletion/delete-item db arg)
    (if (:old-selected-item opts)
      (assoc ((fetch-context {:db db}) opts [(:old-selected-item opts) false]) :item-view? false)
      (let [m {:selected-item nil :item-view? false}]
        (merge {:items (search-items db) :contexts (search-context-items db "" {})} m)))))

(defn- get-selected-secondary-contexts-set
  [{{{{{:keys [selected-secondary-contexts secondary-contexts-inverted]} :current} :views} :data}
      :selected-item}]
  (into #{} (when-not secondary-contexts-inverted selected-secondary-contexts)))

(defn insert-context
  [{:keys [db]}]
  (fn [_state arg]
    (log/info "insert-context")
    {:selected-item (datastore/new-context db arg)
     :aggregated-contexts '()
     :items []
     :q nil
     :active-search :items
     :unassigned-secondary-contexts-selected? false}))

(defn insert-item
  [{:keys [db]}]
  (fn [{:keys [selected-item] :as state} {:keys [title]}]
    (log/info "insert-item")
    (let [secondary-contexts-set (get-selected-secondary-contexts-set state)
          ;; Pasting a link is a gesture at the context you are standing in, so
          ;; it is filed there even when the graph already holds it and the
          ;; ingester hands back what it found. The item view below still opens
          ;; on it, which is how you see that it was already there.
          item (insertion/ensure-contexts!
                 db
                 (insertion/insert-item db title selected-item secondary-contexts-set)
                 (insertion/contexts-of selected-item secondary-contexts-set))]
      (merge {:active-search nil}
             (if (map? item)
               (merge {:item-view? true :old-selected-item selected-item}
                      (let [log-data {:item (select-keys item [:id :title])}]
                        (if (:previously-existing-item? item)
                          (do (log/info log-data "Item already exists - no insertion.")
                              ((fetch-context {:db db}) state [item true]))
                          (do (log/info log-data "Inserted item")
                              (merge (items-under db selected-item state)
                                     {:selected-item selected-item
                                      :item-view? false
                                      :q nil
                                      :aggregated-contexts '()
                                      :item-descriptions nil})))))
               (items-under db (:q state) selected-item state))))))

(defn fetch-item-description
  [{:keys [db]}]
  (fn [state item-ref]
    (let [item (datastore/get-item db item-ref)
          history (datastore/get-description-history db item-ref)
          descriptions (:versions history)]
      (assoc state
        :item-descriptions descriptions
        :item-description (:description item) ; Keep for backward compatibility temporarily
        :ignore-item-description (or (nil? (:description item)) (not (seq (:description item))))))))

(defn fetch-item-provenance
  "The item's CURRENT description together with the caution ranges over it, for
   the Provenance page.

   Both in one command, and that is the point of it existing rather than being
   folded into `fetch-item-description`. The ranges index the lines of one exact
   text; text and ranges fetched separately could be a save apart, and then
   every line under the cursor would be tinted with its neighbour's colour --
   wrong in a way that still looks entirely plausible on screen.

   The current description whatever version the bar is showing, because
   provenance is about the item as such: it says who wrote the text that is
   standing now, and a reader deciding what he may rewrite is deciding about
   that text and no other.

   Its own command rather than a field on the description fetch, because the
   assessment is not free (see `provenance/of-item`) and that fetch runs on
   hover. Here it is paid for once, when the button is pressed."
  [{:keys [db]}]
  (fn [state item-ref]
    (let [item (datastore/get-item db item-ref)]
      (assoc state
        :provenance {:item-id (:id item)
                     :description (:description item)
                     :caution (provenance/of-item db (:id item))}))))

(defn edit-item-in-obsidian
  [{:keys [db]}]
  (fn [state item-ref]
    (let [item (datastore/get-item db item-ref)
          result (opener/create-obsidian-temp-file item)]
      (if (:error result)
        (assoc state :error (:error result))
        (if (:file-already-exists? result)
          ;; If file existed, don't show modal - just return current state
          state
          ;; If file didn't exist, show modal as usual
          (assoc state :modal :external-edit))))))

(defn- sync-from-obsidian
  [{:keys [db]} item-id]
  (try (when-let [description (opener/parse-obsidian-temp-file)]
         (let [item (datastore/get-item db {:id item-id})]
           (when item
             (log/info (str "Synced changes from Obsidian for item" item-id
                            "- saved:" (pr-str description)))
             ;; Use the same update method as regular description updates
             (datastore/update-context-description db {:id item-id :description description} "obsidian"))))
       (catch Exception e
         (log/error {:error-context :obsidian-sync} e "Failed to sync from Obsidian")
         nil)))

(defn sync-obsidian-changes
  [{:keys [db]}]
  (fn [state arg]
    (log/info (str "Sync obsidian changes back" 1))
    (let [item-id (:id arg)]
      (sync-from-obsidian {:db db} item-id)
      (opener/delete-obsidian-temp-file)
      (let [fresh-item (datastore/get-item db {:id item-id})
            history (datastore/get-description-history db {:id item-id})
            descriptions (:versions history)
            new-state (-> state
                          (assoc :modal nil)
                          (assoc :selected-item fresh-item)
                          (assoc :item-descriptions descriptions)
                          (assoc :description-version-idx 0))]
        (log/info (str "Updated selected-item with:" (pr-str (:description fresh-item))))
        new-state))))

(defn get-obsidian-file-content
  [{:keys [_db]}]
  (fn [state]
    (let [content (opener/parse-obsidian-temp-file)] (assoc state :obsidian-file-content content))))

(defn discard-obsidian-changes
  [{:keys [_db]}]
  (fn [state] (opener/delete-obsidian-temp-file) (assoc state :modal nil)))

(defn link-selected-context-to-context
  "link selected context as an item to a container"
  [{:keys [db]}]
  (fn [{:keys [selected-item] :as opts} arg shift-pressed? _alt-pressed?]
    (let [selected-item (or selected-item (datastore/get-item db (:selected-item opts)))]
      (log/info (str "Link selected-item to item '" selected-item "' - " shift-pressed?))
      (datastore/reprioritize-context db arg)
      (datastore.relations/link-item-to-another-item! db selected-item arg (not shift-pressed?))
      (let [fresh-selected-item (datastore/get-item db (:selected-item opts))]
        (merge (items-under db fresh-selected-item opts)
               {:link-context nil
                :active-search nil
                :selected-item fresh-selected-item
                :q nil}
               (when (:selected-item opts)
                 {:aggregated-contexts ((fetch-aggregated-contexts {:db db}) opts)}))))))

(defn search-contexts [db opts] {:contexts (search-context-items db (:q opts) (dissoc opts :q))})

(defn vector-search-related-items
  [{:keys [db]}]
  (fn [{:keys [selected-item q] :as opts}]
    (if (or (nil? q) (str/blank? q))
      {:items []}
      (let [[selected-item-id search-opts]
              (simplify-params (assoc opts :selected-item selected-item))]
        {:items (semsearch/search-related-items-vector
                  db q selected-item-id (assoc search-opts :limit limit))}))))

(defn vector-threshold-search-related-items
  "Blue-mode: original-order related items filtered by a cosine-similarity
   threshold, computed entirely in the backend. Reads :vector-threshold from
   state (nil -> snap to the query's max similarity, only top ties). Returns
   {:items ... :vector-threshold ... :vector-max-similarity ...
    :vector-min-similarity ...} so the slider can position itself."
  [{:keys [db]}]
  (fn [{:keys [selected-item q vector-threshold] :as opts}]
    (if (or (nil? q) (str/blank? q))
      {:items [] :vector-threshold nil :vector-max-similarity nil :vector-min-similarity nil}
      (let [[selected-item-id search-opts]
              (simplify-params (assoc opts :selected-item selected-item))]
        (semsearch/search-related-items-vector-threshold
          db q selected-item-id
          (assoc search-opts :threshold vector-threshold :limit limit))))))

(defn start-linking-selected-item-to-context-with-local-search
  [db opts]
  (log/info "start-linking-selected-item-to-context-with-local-search ")
  {:contexts (search-context-items db "" (assoc opts :link-context true))
   :q ""
   :link-context true
   :active-search :contexts})

(defn- start-context-search
  [db opts]
  {:contexts (search-context-items db "" opts) :q "" :active-search :contexts})

(defn start-linking-item-to-selected-context
  [db opts]
  (log/info "start-linking-item-to-selected-context")
  {:items (search db (merge opts {:link-item true :q ""}))
   :active-search :items
   :link-item true
   :q ""})

(defn finish-linking-item
  [{:keys [db]}]
  (fn [{:keys [selected-item] :as opts} item-id shift-pressed? alt-pressed?]
    (log/info (str "Finish linking item " shift-pressed? " - " alt-pressed?))
    (datastore/reprioritize-item db {:id item-id})
    (let [selected-item' (datastore/get-item db {:id item-id})]
      (if (and shift-pressed? alt-pressed?)
        (do (datastore.relations/link-item-to-another-item! db selected-item' selected-item false)
            (datastore.relations/link-item-to-another-item! db selected-item selected-item' false))
        (datastore.relations/link-item-to-another-item! db
                                                        selected-item'
                                                        selected-item
                                                        (not shift-pressed?)))
      (let [opts (-> opts
                     (dissoc :q :link-item :link-context)
                     (assoc-in [:selected-item :data :views :current :selected-secondary-contexts]
                               []))
            aggregated-contexts ((fetch-aggregated-contexts {:db db}) opts)
            selected-item (datastore/get-item db (:selected-item opts))]
        (merge (items-under db (:selected-item opts) opts)
               {:active-search nil
                :selected-item selected-item
                :link-item nil
                :link-context nil
                :q nil
                :aggregated-contexts aggregated-contexts})))))

(defn reprioritize-item
  [{:keys [db]}]
  (fn [state item]
    (log/info (str "repository/reprioritize-item" (:id item) (:title item)))
    (datastore/reprioritize-item db item)
    (merge {:items (search db (dissoc state :q)) :active-search nil :q nil}
           ;; The same state the list was built from, q and all -- this one drops
           ;; it, so the bound has to drop it too or it would be bounding a list
           ;; that is not the one it was counted for.
           (hierarchy-bound db (dissoc state :q)))))

(defn upgrade-item-to-context
  [{:keys [db]}]
  (fn [{:keys [selected-item]}]
    (log/info (str "repository/upgrade-item-to-context" (:id selected-item)))
    {:selected-item (datastore/switch-between-item-and-context! db selected-item)}))

(defn- unlink-refusal-or-state
  "What a declined unlink answers with: the state the user is looking at,
   unchanged, carrying the reason when the rule had one to give. Both unlink
   paths end here rather than returning a bare `state`, which is what made the
   gesture look broken rather than declined.

   Answered in band, the way the acyclicity refusal is (see update-item) -- but
   under its own key, not :part-of-refused. The two are the same class of message
   and are shown in the same banner (ui.refusal); the key is what differs,
   because :part-of-refused means \"the open edit modal's save was refused\" and
   ui.modals/save-notice reads it whenever that modal is up. Sharing it would
   leave an unlink refused out in the list waiting inside the next modal opened.

   Nothing else is refreshed alongside it: the write did not happen, so the list
   did not change, and re-answering it would only move the row under a message
   saying nothing happened."
  [state item whole]
  (if-let [msg (and whole (datastore.relations/last-container-refusal item whole))]
    (do (log/info {:event "last-container-refused" :item-id (:id item)} msg)
        (assoc state :unlink-refused msg))
    state))

(defn unlink-selected-item-from-container
  [{:keys [db]}]
  (fn [{:keys [selected-item old-selected-item] :as state}]
    (log/info (str "repository/unlink-selected-item-from-container - Try removing " (:id
                                                                                      selected-item)
                   ":" (:title selected-item)
                   " from " (:id old-selected-item)
                   ":" (:title old-selected-item)))
    (if (or (not (datastore.relations/unlink-item-from-another-item! db
                                                                     selected-item
                                                                     old-selected-item))
            (not old-selected-item))
      (unlink-refusal-or-state state selected-item old-selected-item)
      (do (log/info (str "repository/unlink-selected-item-from-container - Removing now"))
          (merge (items-under db old-selected-item state)
                 {:selected-item old-selected-item
                  :aggregated-contexts ((fetch-aggregated-contexts {:db db})
                                         (assoc state :selected-item old-selected-item))
                  :item-view? false})))))

(defn unlink-item
  "Unlink a row from the whole it is shown under.

   `whole` is that whole, sent by the client because only the client knows which
   list the row was clicked in: in hierarchy mode below level 1 a row is shown
   under the selected context and filed under something further down, and the
   relation the user is pointing at is the second one. Absent -- every caller
   before this and every list but that one -- it is the selected context, which
   is what it always was."
  [{:keys [db]}]
  (fn [{:keys [selected-item] :as state} item & [whole]]
    (let [whole (or whole selected-item)]
      (log/info (str "unlink item " (:title item) " from " (:title whole)))
      (if-not whole
        (throw (Exception. "unlink-item shouldn't have been called without 'selected-item'"))
        (if (datastore.relations/unlink-item-from-another-item! db item whole)
          (merge {:items (search db state) :item-view? false} (hierarchy-bound db state))
          (unlink-refusal-or-state {} item whole))))))

(defn select-last-context
  [{:keys [db]}]
  (fn [{:keys [old-selected-item] :as state}]
    (if-not old-selected-item
      {}
      (do (log/info (str "repository/select-last-context - " (:id old-selected-item)
                         ":" (:title old-selected-item)))
          (merge (items-under db old-selected-item state)
                 {:selected-item old-selected-item :item-view? false})))))

(defn update-item
  [{:keys [db]}]
  (fn [state arg]
    (let [context (or (:context (:context arg)) (:context arg))
          item-contexts (:item-contexts arg)]
      (log/info (str "repository/update-item" (:id context) "-" (:title context) "-" arg))
      (try
        (let [is_context (:is_context (datastore/get-item db context))]
          (datastore.relations/set-the-containers-of-item! db context item-contexts is_context))
        (let [selected-item (datastore/update-item db context)]
          ;; The edit modal stays open until this answers -- see
          ;; ui.modals.actions/update-context! -- so closing it is this
          ;; function's to say, and only on the path that saved something.
          (merge (items-under db selected-item state)
                 {:selected-item selected-item :modal nil :q nil}))
        ;; Both ways this can fail are answered in band, the way a refused write
        ;; on a replica is answered: nothing else in the response, so the list
        ;; the user is looking at stays as it was, and the modal they made the
        ;; edit in stays open with everything they typed still in it -- a
        ;; checkbox that silently fails to stick is worse than an error, and a
        ;; refusal that throws the rest of the edit away is worse than both.
        (catch Exception e
          (if-let [msg (part-of/cycle-refusal e)]
            (do (log/info {:event "part-of-cycle-refused" :item-id (:id context)} msg)
                {:part-of-refused msg :modal :edit-context})
            ;; Anything else -- most reachably another writer holding the
            ;; database, since the save takes a write transaction now and the
            ;; pollers and /api write too. Letting it throw put the SPA's
            ;; go-block into an error it has no branch for: the modal sat there
            ;; with :loading never cleared and nothing said at all.
            (do (log/error e "repository/update-item failed")
                {:save-failed (.getMessage e) :modal :edit-context})))))))

(defn update-annotations
  "What the relation modal saves: the item's own subtitle, the annotation on the
   edge the card was shown by, and -- since that modal edits the relation and no
   longer only its annotation -- that edge's badge and part-of standing.

   The standing is written first because it is the only write here that can be
   refused: an edge ticked `part of` may close a loop, and et.vp.ds.part-of
   throws rather than write it. Going first is what makes \"nothing was saved\"
   true of a refusal -- the two annotations below have not been written yet.

   The modal stays open across the save and this closes it, the arrangement
   update-item already has with the edit modal (see ui.modals.actions), and for
   the same reason: a refused save has to leave the user with everything they
   typed still in front of them."
  [{:keys [db]}]
  (fn [state
       {:keys [item-id context-id global-annotation relation-annotation relation-standing]}]
    (log/info (str "repository/update-annotations item-id: " item-id
                   " context-id: " context-id
                   " global: " global-annotation
                   " relation: " relation-annotation
                   " standing: " relation-standing))
    (try
      (when (and context-id (seq relation-standing))
        (datastore.relations/update-relation-standing! db item-id context-id relation-standing))
      (when global-annotation
        ;; Fetch the existing item to preserve all its properties
        (let [existing-item (datastore/get-item db {:id item-id})]
          (datastore/update-item db (assoc existing-item :annotation global-annotation))))
      (when (and context-id relation-annotation)
        (datastore.relations/update-relation-annotation! db item-id context-id relation-annotation))
      ;; The list to answer with is the one under the selected context, which is
      ;; not the same thing as the whole whose edge was just annotated. It used to
      ;; be: the modal was always handed the selected context, so re-reading
      ;; `context-id` re-read the selection and this was a refresh. Below level 1
      ;; the annotated edge belongs to a whole further down (see
      ;; ui.actions/filed-under), and re-reading that one here would answer an
      ;; annotation edit by navigating somewhere the user did not ask to go.
      (let [selected-item (when-let [id (:id (:selected-item state))]
                            (datastore/get-item db {:id id}))]
        (if selected-item
          (merge (items-under db selected-item state)
                 {:selected-item selected-item :modal nil :q nil})
          {:items (search db {:q "" :selected-item nil})
           :contexts (search-context-items db "" {})
           :modal nil
           :q nil}))
      (catch Exception e
        (if-let [msg (part-of/cycle-refusal e)]
          (do (log/info {:event "part-of-cycle-refused" :item-id item-id :context-id context-id}
                        msg)
              {:part-of-refused msg :modal :annotation-edit})
          ;; Same fail-open-in-band answer update-item gives: nothing else in the
          ;; response, so the list stays as it was and the modal stays up with
          ;; what was typed in it.
          (do (log/error e "repository/update-annotations failed")
              {:save-failed (.getMessage e) :modal :annotation-edit}))))))

(defn list-resources
  [{:keys [db]}]
  (fn [{:keys [cmd arg active-search _selected-item] :as opts}]
    (log-opts opts)
    ;; {:clj-kondo/ignore [:unresolved-var]} ;;;
    (merge
      {:cmd nil :arg nil}
      (case cmd
        nil (cond (= :items active-search)
                    (merge {:items (search db opts)} (hierarchy-bound db opts))
                  (= :contexts active-search) (search-contexts db opts)
                  :else (merge {:items (search db opts) :contexts (search-context-items db "" {})}
                               (hierarchy-bound db opts)))
        :link-item-to-selected-item (start-linking-item-to-selected-context db opts)
        :start-linking-selected-item-to-context
          (start-linking-selected-item-to-context-with-local-search db opts)
        :start-context-search (start-context-search db opts)
        :update-context-description (let [updated-item (datastore/update-context-description db arg "app")
                                          history (datastore/get-description-history db arg)
                                          descriptions (:versions history)]
                                      {:selected-item updated-item
                                       :item-descriptions descriptions
                                       :description-version-idx 0})
        ;; TODO remove :else clause. fix where there are cases where this fires but there
        ;; shoulnd't be
        :else {}))))

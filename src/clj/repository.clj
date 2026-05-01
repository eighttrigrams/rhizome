(ns repository
  (:require [mount.core :as mount]
            [et.vp.ds :as datastore]
            [et.vp.ds.search :as search]
            [et.vp.ds.relations :as datastore.relations]
            [cambium.core :as log]
            [repository.insertion :as insertion]
            [repository.deletion :as deletion]
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

(mount/defstate repository
                :start (do (tap> [:resources :up 2])
                           [{:id 1 :name "one"} {:id 2 :name "two"}
                            {:id 3 :name "three" :protected true}])
                :stop (do (tap> [:resources :down]) nil))

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
      (if fetch-as-item?
        (datastore/reprioritize-item db arg)
        (datastore/reprioritize-context db arg))
      (merge opts
             {:selected-item selected-item
              :items (search-related-items db "" selected-item)
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
      {:items (search-related-items db (:q opts) (:selected-item opts))})))

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
      {:items (search-related-items db (:q opts) context)
       :contexts (search-context-items db "" {})
       :selected-item context})))

(defn store-current-view
  [{:keys [db]}]
  (fn [{:keys [selected-item]} item]
    (let [selected-item (datastore/store-current-view db selected-item item)]
      {:selected-item selected-item})))

(defn load-stored-context
  [{:keys [db]}]
  (fn [{:keys [selected-item] :as opts} idx]
    (let [selected-item (datastore/load-stored-context db selected-item idx)]
      {:selected-item selected-item :items (search-related-items db (:q opts) selected-item)})))

(defn remove-stored-context
  [{:keys [db]}]
  (fn [{:keys [selected-item]} idx]
    (let [selected-item (datastore/remove-stored-context db selected-item idx)]
      {:selected-item selected-item})))

(defn cycle-search-mode
  [{:keys [db]}]
  (fn [{:keys [selected-item] :as opts}]
    (let [selected-item (datastore/cycle-search-mode db selected-item)]
      {:selected-item selected-item :items (search-related-items db (:q opts) selected-item)})))

(defn delete-item
  [{:keys [db]}]
  (fn [opts item] (deletion/delete-item db item) {:items (search db opts) :item-view? false}))

(defn preview-deletion-of-related-items
  [{:keys [db]}]
  (fn [{:keys [selected-item] :as _opts}]
    (if-not selected-item
      {:danger-preview-items []}
      {:danger-preview-items (search-related-items db "" selected-item)})))


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
    (let [item (insertion/insert-item db
                                      title
                                      selected-item
                                      (get-selected-secondary-contexts-set state))]
      (merge {:active-search nil}
             (if (map? item)
               (merge {:item-view? true :old-selected-item selected-item}
                      (let [log-data {:item (select-keys item [:id :title])}]
                        (if (:previously-existing-item? item)
                          (do (log/info log-data "Item already exists - no insertion.")
                              ((fetch-context {:db db}) state [item true]))
                          (do (log/info log-data "Inserted item")
                              {:items (search-related-items db "" selected-item)
                               :selected-item selected-item
                               :item-view? false
                               :q nil
                               :aggregated-contexts '()
                               :item-descriptions nil}))))
               {:items (search-related-items db (:q state) selected-item)})))))

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
             (datastore/update-context-description db {:id item-id :description description}))))
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
        (merge {:link-context nil
                :active-search nil
                :selected-item fresh-selected-item
                :items (search-related-items db "" fresh-selected-item)
                :q nil}
               (when (:selected-item opts)
                 {:aggregated-contexts ((fetch-aggregated-contexts {:db db}) opts)}))))))

(defn search-contexts [db opts] {:contexts (search-context-items db (:q opts) (dissoc opts :q))})

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
            items (search-related-items db "" (:selected-item opts))
            aggregated-contexts ((fetch-aggregated-contexts {:db db}) opts)
            selected-item (datastore/get-item db (:selected-item opts))]
        {:items items
         :active-search nil
         :selected-item selected-item
         :link-item nil
         :link-context nil
         :q nil
         :aggregated-contexts aggregated-contexts}))))

(defn reprioritize-item
  [{:keys [db]}]
  (fn [state item]
    (log/info (str "repository/reprioritize-item" (:id item) (:title item)))
    (datastore/reprioritize-item db item)
    {:items (search db (dissoc state :q)) :active-search nil :q nil}))

(defn upgrade-item-to-context
  [{:keys [db]}]
  (fn [{:keys [selected-item]}]
    (log/info (str "repository/upgrade-item-to-context" (:id selected-item)))
    {:selected-item (datastore/switch-between-item-and-context! db selected-item)}))

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
      state
      (do (log/info (str "repository/unlink-selected-item-from-container - Removing now"))
          {:selected-item old-selected-item
           :items (search-related-items db "" old-selected-item)
           :aggregated-contexts ((fetch-aggregated-contexts {:db db})
                                  (assoc state :selected-item old-selected-item))
           :item-view? false}))))

(defn unlink-item
  [{:keys [db]}]
  (fn [{:keys [selected-item] :as state} item]
    (log/info (str "unlink item " (:title item) " from " (:title selected-item)))
    (if-not selected-item
      (throw (Exception. "unlink-item shouldn't have been called without 'selected-item'"))
      (do (datastore.relations/unlink-item-from-another-item! db item selected-item)
          {:items (search db state) :item-view? false}))))

(defn select-last-context
  [{:keys [db]}]
  (fn [{:keys [old-selected-item]}]
    (if-not old-selected-item
      {}
      (do (log/info (str "repository/select-last-context - " (:id old-selected-item)
                         ":" (:title old-selected-item)))
          {:selected-item old-selected-item
           :items (search-related-items db "" old-selected-item)
           :item-view? false}))))

(defn update-item
  [{:keys [db]}]
  (fn [_opts arg]
    (let [context (or (:context (:context arg)) (:context arg))
          item-contexts (:item-contexts arg)]
      (log/info (str "repository/update-item" (:id context) "-" (:title context) "-" arg))
      (let [is_context (:is_context (datastore/get-item db context))]
        (datastore.relations/set-the-containers-of-item! db context item-contexts is_context))
      (let [selected-item (datastore/update-item db context)]
        (merge {:selected-item selected-item
                :items (search-related-items db "" selected-item)
                :q nil})))))

(defn update-annotations
  [{:keys [db]}]
  (fn [_opts {:keys [item-id context-id global-annotation relation-annotation]}]
    (log/info (str "repository/update-annotations item-id: " item-id
                   " context-id: " context-id
                   " global: " global-annotation
                   " relation: " relation-annotation))
    (when global-annotation
      ;; Fetch the existing item to preserve all its properties
      (let [existing-item (datastore/get-item db {:id item-id})]
        (datastore/update-item db (assoc existing-item :annotation global-annotation))))
    (when (and context-id relation-annotation)
      (datastore.relations/update-relation-annotation! db item-id context-id relation-annotation))
    (let [selected-item (when context-id (datastore/get-item db {:id context-id}))]
      (if selected-item
        {:selected-item selected-item :items (search-related-items db "" selected-item) :q nil}
        {:items (search db {:q "" :selected-item nil})
         :contexts (search-context-items db "" {})
         :q nil}))))

(defn list-resources
  [{:keys [db]}]
  (fn [{:keys [cmd arg active-search _selected-item] :as opts}]
    (log-opts opts)
    ;; {:clj-kondo/ignore [:unresolved-var]} ;;;
    (merge
      {:cmd nil :arg nil}
      (case cmd
        nil (cond (= :items active-search) {:items (search db opts)}
                  (= :contexts active-search) (search-contexts db opts)
                  :else (merge {:items (search db opts) :contexts (search-context-items db "" {})}))
        :link-item-to-selected-item (start-linking-item-to-selected-context db opts)
        :start-linking-selected-item-to-context
          (start-linking-selected-item-to-context-with-local-search db opts)
        :start-context-search (start-context-search db opts)
        :update-context-description (let [updated-item (datastore/update-context-description db arg)
                                          history (datastore/get-description-history db arg)
                                          descriptions (:versions history)]
                                      {:selected-item updated-item
                                       :item-descriptions descriptions
                                       :description-version-idx 0})
        ;; TODO remove :else clause. fix where there are cases where this fires but there
        ;; shoulnd't be
        :else {}))))

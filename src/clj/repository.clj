(ns repository
  (:require [mount.core :as mount]
            [et.vp.ds :as datastore]
            [et.vp.ds.search :as search]
            [et.vp.ds.relations :as datastore.relations]
            [cambium.core :as log]
            [repository.insertion :as insertion]
            [repository.deletion :as deletion]))

(defn search-aggregated-contexts
  [db {{{:keys [highlighted-secondary-contexts]} :data} :selected-context
       :as opts}]
  (let [items (search/search-related-items
                 db 
                 "" 
                 (:id (:selected-context opts))
                 {}
                 {})]
    (search/get-aggregated-contexts db items highlighted-secondary-contexts)))

(defn- simplify-params [{:keys [selected-context] :as opts}]
  (let [selected-context-id (:id selected-context)
        opts (-> opts 
                 (merge (select-keys (-> selected-context :data :views :current) 
                                     [:secondary-contexts-inverted
                                      :secondary-contexts-unassigned-selected
                                      :selected-secondary-contexts
                                      :search-mode]))
                 (dissoc :selected-context)
                 (assoc :selected-context-id (:id selected-context)))]
    [selected-context-id opts]))

(def limit 100)

(defn- search'
  "Prefer calling search-items or search-related-items"
  [db q selected-context-id {:keys [link-item selected-context] :as opts}]
  (log/info (str "search:" selected-context-id " link-item:" link-item))
  (when selected-context (throw (IllegalArgumentException. "'selected-context' not expected as an argument here")))
  (if selected-context-id
   (if link-item
     (search/search-items db 
                   q
                   (assoc opts
                          :all-items? true
                          :selected-context-id selected-context-id)
                   {:limit limit})
     (search/search-related-items db 
                           q
                           selected-context-id
                           opts
                           {}))
    (search/search-items db q (assoc opts :all-items? true) {:limit limit})))

(defn- search-items [db]
  (search/search-items db "" {:all-items? true} {:limit limit}))

(defn- search [db {:keys [q] :as opts}]
  (let [[selected-context-id opts] (simplify-params opts)]
    (search' db q selected-context-id opts)))

;; TODO this seems to replicate what's done in ds namespace (see update-contexts fn there)
(defn- update-contexts [item]
  (update-in item [:data :contexts] 
             (fn [contexts]
               (into {} 
                     (map (fn [[k v]]
                            [k (if (map? v) v
                                   {:title       v
                                    :show-badge? true})])
                          contexts)))))

(defn- search-context-items [db q opts]
  (let [[_selected-context-id opts] (simplify-params opts)]
    (map update-contexts (search/search-items db q opts {:limit limit}))))

(defn- search-related-items 
  ([db q selected-context]
   (search-related-items db q selected-context {}))
  ([db q selected-context opts]
   (let [[selected-context-id opts] (simplify-params (assoc opts :selected-context selected-context))]
         (search/search-related-items db 
                                      q 
                                      selected-context-id
                                      opts 
                                      {}))))

(mount/defstate repository
  :start (do
           (tap> [:resources :up 2])
           [{:id   1
             :name "one"}
            {:id   2
             :name "two"}
            {:id        3
             :name      "three"
             :protected true}])
  :stop (do 
          (tap> [:resources :down])
          nil))

(defn- log-opts [{:keys [cmd q active-search] :as _opts}]
  (log/debug (str "list-resources - "
                 (or cmd (str active-search "(" q ")")))))

(defn fetch-aggregated-contexts [{:keys [db]}]
  (fn [state]
    (when-not (:selected-context state) (throw (Exception. "fetch-aggregated-contexts called without selected-context")))
    (search-aggregated-contexts db state)))

(defn fetch-context [{:keys [db]}]
  (fn [old-state [arg fetch-as-item?]]
    (let [selected-context (datastore/get-item db arg)
          opts             {:selected-context selected-context}]
      (log/info (str "fetch-context as " (if fetch-as-item? "item" "context") " from (" (:id (:old-selected-context old-state)) "):\"" (:title (:old-selected-context old-state)) "\" to (" (:id selected-context) "):\"" (:title selected-context) "\""))
      (if fetch-as-item?
        (datastore/reprioritize-item db arg)
        (datastore/reprioritize-context db arg))
      (merge opts
             {:selected-context                        selected-context
              :items (search-related-items db "" selected-context)
              :context-to-fetch                        nil
              :unassigned-secondary-contexts-selected? false
              :q                                       nil}))))

(defn deselect-context [{:keys [db]}]
  (fn [_opts]
    (log/info (str "deselect context"))
    {:items           (search-items db)
     :contexts         (search-context-items db "" {})
     :selected-context nil
     :q                nil}))

(defn the-future [arg]
  (future arg))

(defn- change-secondary-contexts-operation [db]
  (fn [opts]
    (log/info (str "repository/change-secondary-contexts-operation: " 
                   (:id (:selected-context opts))
                   "-"
                   (:title (:selected-context opts))))
    (let [_context (the-future (datastore/update-item db (:selected-context opts)))]
      {:items (search-related-items db (:q opts) (:selected-context opts))})))

(defn change-secondary-contexts-selection [{:keys [db]}]
  (change-secondary-contexts-operation db))

(defn change-secondary-contexts-unassigned-selected [{:keys [db]}]
  (change-secondary-contexts-operation db))

(defn change-secondary-contexts-inverted [{:keys [db]}]
  (change-secondary-contexts-operation db))

(defn deselect-secondary-contexts [{:keys [db]}]
  (fn [opts]
    (let [context (datastore/update-item db (:selected-context
                                             (-> opts
                                                 (assoc-in
                                                  [:selected-context
                                                   :data
                                                   :views
                                                   :current
                                                   :selected-secondary-contexts]
                                                  [])
                                                 (assoc-in
                                                  [:selected-context
                                                   :data
                                                   :views
                                                   :current
                                                   :secondary-contexts-inverted]
                                                  false)
                                                 (assoc-in
                                                  [:selected-context
                                                   :data
                                                   :views
                                                   :current
                                                   :secondary-contexts-unassigned-selected]
                                                  false)
                                                 (assoc-in
                                                  [:selected-context
                                                   :data
                                                   :views
                                                   :current
                                                   :search-mode]
                                                  0)
                                                 (assoc-in
                                                  [:selected-context
                                                   :data
                                                   :views
                                                   :current
                                                   :notes-mode]
                                                  false)
                                                 )))]
      {:items                          (search-related-items
                                         db
                                         (:q opts)
                                         context)
       :contexts                        (search-context-items db "" {})
       :selected-context context})))

(defn store-current-view [{:keys [db]}]
  (fn [{:keys [selected-context]} item]
    (let [selected-context (datastore/store-current-view db selected-context item)]
      {:selected-context selected-context})))

(defn load-stored-context [{:keys [db]}]
  (fn [{:keys [selected-context] :as opts} idx]
    (let [selected-context (datastore/load-stored-context db selected-context idx)]
      {:selected-context selected-context
       :items (search-related-items db (:q opts) selected-context)})))

(defn remove-stored-context [{:keys [db]}]
  (fn [{:keys [selected-context]} idx]
    (let [selected-context (datastore/remove-stored-context db selected-context idx)]
      {:selected-context selected-context})))

(defn cycle-search-mode [{:keys [db]}]
  (fn [{:keys [selected-context] :as opts}]
    (let [selected-context (datastore/cycle-search-mode db selected-context)]
      {:selected-context selected-context
       :items (search-related-items db (:q opts) selected-context)})))

(defn delete-item [{:keys [db]}]
  (fn [opts item]
    (deletion/delete-item db item)
    {:items (search db opts)
     :item-view? false}))

(defn delete-context [{:keys [db]}]
  (fn [opts arg]
    (deletion/delete-item db arg)
    (if (:old-selected-context opts)
      (assoc ((fetch-context {:db db}) opts [(:old-selected-context opts) false])
             :item-view? false)
      (let [m {:selected-context nil :item-view? false}]
        (merge {:items (search-items db)
                :contexts (search-context-items db "" {})}
               m)))))

(defn- get-selected-secondary-contexts-set 
  [{{{{{:keys [selected-secondary-contexts
               secondary-contexts-inverted]} :current} :views} :data} :selected-context}]
  (into #{}  (when-not 
               secondary-contexts-inverted
               selected-secondary-contexts)))

(defn insert-context [{:keys [db]}]
  (fn [_state arg]
    (log/info "insert-context")
    {:selected-context                        (datastore/new-context db arg)
     :aggregated-contexts                     '()
     :items                                  []
     :q                                       nil
     :active-search                           :items
     :unassigned-secondary-contexts-selected? false}))

(defn insert-item [{:keys [db]}]
  (fn [{:keys [selected-context]
        :as state} 
       {:keys [title]}]
    (log/info "insert-item")
    (let [item (insertion/insert-item db 
                                       title
                                       selected-context 
                                       (get-selected-secondary-contexts-set state))]
      (merge
       {:active-search nil}
       (if (map? item)
         (merge 
          {:item-view? true
           :old-selected-context selected-context}
          (let [log-data {:item (select-keys item [:id :title])}]
            (if (:previously-existing-item? item)
              (do 
                (log/info log-data "Item already exists - no insertion.") 
                ((fetch-context {:db db}) state [item true]))
              (do
                (log/info log-data "Inserted item")
                {:items              '()
                 :selected-context    (datastore/get-item db item) ;; fetch again to have latest relations from two lines above
                 :q                   nil
                 :aggregated-contexts '()}))))
         {:items (search-related-items db (:q state) selected-context)})))))

(defn fetch-item-description [{:keys [db]}]
  (fn [state item-ref]
    (let [item (datastore/get-item db item-ref)]
     (assoc state 
            :item-description (:description item)
            :ignore-item-description (or (nil? (:description item)) (not (seq (:description item))))))))

(defn link-selected-context-to-context "link selected context as an item to a container"
  [{:keys [db]}]
  (fn 
    [{:keys [selected-item selected-context] :as opts} arg shift-pressed? _alt-pressed?]
    (let [selected-item (or selected-item
                             (datastore/get-item db selected-context))]
      (log/info (str "repository/link-selected-context-to-context " selected-item " - " shift-pressed?))
      (datastore/reprioritize-context db arg)
      (datastore.relations/link-item-to-another-item! db selected-item arg (not shift-pressed?))
      (let [fresh-selected-context (datastore/get-item db selected-context)]
        (merge {:link-context   nil
                :active-search  nil
                :selected-context fresh-selected-context
                :items         (search-related-items db "" fresh-selected-context)
                :q              nil}
               (when selected-context
                 {:aggregated-contexts ((fetch-aggregated-contexts {:db db}) opts)}))))))

(defn search-contexts [db opts]
  {:contexts (search-context-items db (:q opts) (dissoc opts :q))})

(defn start-linking-selected-item-to-context-with-local-search 
  [db opts]
  (log/info "start-linking-selected-item-to-context-with-local-search ")
  {:contexts (search-context-items db "" (assoc opts :link-context true))
   :q ""
   :link-context true
   :active-search :contexts})

(defn- start-context-search [db opts]
  {:contexts (search-context-items db "" opts)
   :q ""
   :active-search :contexts})

(defn start-linking-item-to-selected-context 
  [db opts]
  (log/info "start-linking-item-to-selected-context")
  {:items (search
            db 
            (merge opts
                   {:link-item true
                    :q ""}))
   :active-search    :items
   :link-item       true
   :q                ""})

(defn finish-linking-item [{:keys [db]}]
  (fn [{:keys [selected-context] :as opts} item-id shift-pressed? alt-pressed?]
    (log/info (str "finish-linking-item " shift-pressed? " - " alt-pressed?))
    (datastore/reprioritize-item db {:id item-id})
    (let [selected-item (datastore/get-item db {:id item-id})] 
      
      (if (and shift-pressed? alt-pressed?)
        (do 
          (datastore.relations/link-item-to-another-item! db selected-item selected-context true)
          (datastore.relations/link-item-to-another-item! db selected-context selected-item true))
        (datastore.relations/link-item-to-another-item! db selected-item selected-context (not shift-pressed?)))

      (let [opts                (-> opts
                                    (dissoc :q
                                            :link-item
                                            :link-context)
                                    (assoc-in [:selected-context 
                                               :data 
                                               :views 
                                               :current 
                                               :selected-secondary-contexts] []))
            items (search-related-items db "" (:selected-context opts))
            aggregated-contexts ((fetch-aggregated-contexts {:db db}) opts)
            selected-context (datastore/get-item db selected-context)]
        {:items              items
         :active-search       nil
         :selected-context selected-context
         :link-item          nil
         :link-context        nil
         :q                   nil
         :aggregated-contexts aggregated-contexts}))))

(defn reprioritize-item [{:keys [db]}]
  (fn [state item]
    (log/info (str "repository/reprioritize-item" (:id item) (:title item)))
    (datastore/reprioritize-item db item)
    {:items           (search db (dissoc state :q))
     :active-search    nil
     :q                nil}))

(defn upgrade-item-to-context [{:keys [db]}]
  (fn [{:keys [selected-context]}]
    (log/info (str "repository/upgrade-item-to-context" (:id selected-context)))
    {:selected-context (datastore/switch-between-item-and-context! db selected-context)}))

(defn unlink-selected-item-from-container [{:keys [db]}]
  (fn [{:keys [selected-context old-selected-context] :as state}]
    (log/info (str "repository/unlink-selected-item-from-container - Try removing " (:id selected-context) ":" (:title selected-context) " from " (:id old-selected-context) ":" (:title old-selected-context)))
    (if (or (not (datastore.relations/unlink-item-from-another-item! db selected-context old-selected-context))
            (not old-selected-context))
      state
      (do
        (log/info (str "repository/unlink-selected-item-from-container - Removing now"))
        {:selected-context old-selected-context
         :items (search-related-items 
                  db 
                  ""
                  old-selected-context)
         :aggregated-contexts ((fetch-aggregated-contexts {:db db}) (assoc state :selected-context old-selected-context))
         :item-view? false}))))

(defn unlink-item [{:keys [db]}]
  (fn [{:keys [selected-context] :as state} item]
    (log/info (str "unlink item " (:title item) " from " (:title selected-context)))
    (if-not selected-context
      (throw (Exception. "unlink-item shouldn't have been called without 'selected-context'"))
      (do
        (datastore.relations/unlink-item-from-another-item! db item selected-context)
        {:items (search db state)
         :item-view? false}))))

(defn select-last-context [{:keys [db]}]
  (fn [{:keys [old-selected-context]}]
    (if-not old-selected-context
      {}
      (do 
        (log/info (str "repository/select-last-context - " (:id old-selected-context) ":" (:title old-selected-context)))
        {:selected-context    old-selected-context
         :items              (search-related-items 
                               db
                               ""
                               old-selected-context)
         :item-view?         false}))))

(defn update-item [{:keys [db]}]
  (fn [_opts arg]
    (let [context (or (:context (:context arg)) (:context arg))
          item-contexts (:item-contexts arg)]
      (log/info (str "repository/update-item" (:id context) "-" (:title context) "-" arg))
      (let [is_context (:is_context (datastore/get-item db context))]
        (datastore.relations/set-the-containers-of-item! db context item-contexts is_context))
      (let [selected-context (datastore/update-item db context)]
        (merge {:selected-context selected-context
                :items           (search-related-items db "" selected-context)
                :q                nil})))))

(defn list-resources [{:keys [db]}]
  (fn [{:keys [cmd
               arg
               active-search
               _selected-context]
        :as opts}] 
    (log-opts opts) 
    ;; {:clj-kondo/ignore [:unresolved-var]} ;;;
    (merge
     {:cmd                             nil
      :arg                             nil}
     (case cmd
       nil
       (cond (= :items active-search)
             {:items (search db opts)}
             (= :contexts active-search) (search-contexts db opts)
             :else
             (merge {:items   (search db opts)
                     :contexts (search-context-items db "" {})}))
       :link-item-to-selected-context (start-linking-item-to-selected-context db opts)
       :start-linking-selected-item-to-context (start-linking-selected-item-to-context-with-local-search db opts)
       :start-context-search (start-context-search db opts)
       :update-context-description
       {:selected-context (datastore/update-context-description db arg)}

         ;; TODO remove :else clause. fix where there are cases where this fires but there shoulnd't be
       :else {}))))

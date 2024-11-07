(ns repository ;; data-driven logic
  (:require [mount.core :as mount]
            [datastore.config :as config]
            datastore
            privacy
            [datastore.search :as search]
            [datastore.relations :as datastore.relations]
            [cambium.core :as log]
            [repository.insertion :as insertion]
            [repository.deletion :as deletion]))

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

#_{:clj-kondo/ignore [:unresolved-var]}
(defn get-contexts [q]
  (let [db (:db config/config)]
    (search/search-contexts db q)))

(defn- log-opts [{:keys [cmd q active-search] :as _opts}]
  (log/debug (str "list-resources - "
                 (or cmd (str active-search "(" q ")")))))

;; TODO move to other place
(defn flip-privacy [{:keys [privacy-mode]}]
  (fn [_opts]
    (swap! privacy/*public? not)
    {:public? (and @privacy/*public? (= :private privacy-mode))}))

(defn fetch-context [{:keys [db]}]
  (fn [old-state [arg fetch-as-issue?]]
    (let [selected-context (datastore/get-item db arg)
          opts             {:search-globally? false
                            :selected-context selected-context}]
      (log/info (str "fetch-context as " (if fetch-as-issue? "issue" "context") " from (" (:id (:old-selected-context old-state)) "):\"" (:title (:old-selected-context old-state)) "\" to (" (:id selected-context) "):\"" (:title selected-context) "\""))
      (if fetch-as-issue?
        (datastore/reprioritize-issue db arg)
        (datastore/reprioritize-context db arg))
      (merge opts
             {:selected-context                        selected-context
                ;; :active-search                           :issues
              :issues                                  (search/search-issues db 
                                                                             (-> opts
                                                                                 (dissoc :q)
                                                                                 (assoc :skip-context-aggregation? true)))
              :context-to-fetch                        nil
              :unassigned-secondary-contexts-selected? false
              :q                                       nil}))))

(defn the-future [arg]
  (future arg))

(defn- change-secondary-contexts-operation [db]
  (fn [opts]
    (log/info (str "repository/change-secondary-contexts-operation" (:selected-context opts)))
    (let [_context (the-future (datastore/update-item db (:selected-context opts)))]
      {:issues (search/search-issues db (assoc opts :skip-context-aggregation? true))})))

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
                                                   :events-view]
                                                  0)
                                                 (assoc-in
                                                  [:selected-context
                                                   :data
                                                   :views
                                                   :current
                                                   :notes-mode]
                                                  false)
                                                 )))]
      {:issues                          (search/search-issues
                                         db
                                         (assoc opts :selected-context context))
       :contexts                        (search/search-contexts db "")
       :selected-context context})))

(defn make-search-issues
  [{:keys [link-issue
           search-globally?]
    :as   opts}]
  (if (or (= :issue link-issue)
          search-globally?)
    (-> opts
        (cond-> :selected-context
          (update :selected-context (fn [a] (dissoc a :search_mode))))
        (assoc :events-view 0)
        #_(assoc-in [:selected-context 
                   :data 
                   :views
                   :current
                   :selected-secondary-contexts] [])
        (assoc-in [:selected-context
                   :data
                   :views
                   :current
                   :secondary-contexts-inverted] false)
        (assoc-in [:selected-context
                   :data
                   :views
                   :current
                   :secondary-contexts-unassigned-selected] false)
        (assoc-in [:selected-context
                   :data
                   :views
                   :current
                   :events-view] 0)
        (assoc-in [:selected-context
                   :data
                   :views
                   :current
                   :search-mode] 0))
    opts))

(defn- make-events-fn [db mode-number db-fn]
  (fn [{:keys [selected-context] :as opts}]
    (if selected-context
      (let [selected-context (db-fn db selected-context)]
        {:issues         (search/search-issues db (assoc opts :selected-context selected-context))
         :selected-context selected-context
         :contexts       []
         :q              nil})
      (let [opts (-> opts
                     (dissoc :q)
                     (assoc :events-view mode-number))]
        {:issues                          (search/search-issues db opts)
         :contexts                        (if (= 0 (:events-view opts))
                                            (search/search-contexts db opts)
                                            [])
         :events-view (:events-view opts)
         :q                               nil}))))

(defn show-events [{:keys [db]}]
  (make-events-fn db 1 datastore/show-events))

(defn show-past-events [{:keys [db]}]
  ;; currently only used in global mode, so the else branch won't execute
  (make-events-fn db 2 datastore/show-past-events))

(defn deselect-events [{:keys [db]}]
  ;; currently only used in global mode, so the else branch won't execute
  (make-events-fn db 0 datastore/deselect-events))

(defn store-current-view [{:keys [db]}]
  (fn [{:keys [selected-context]} item]
    (let [selected-context (datastore/store-current-view db selected-context item)]
      {:selected-context selected-context})))

(defn load-stored-context [{:keys [db]}]
  (fn [{:keys [selected-context] :as opts} idx]
    (let [selected-context (datastore/load-stored-context db selected-context idx)]
      {:selected-context selected-context
       :issues (search/search-issues db (assoc opts :selected-context selected-context))})))

(defn remove-stored-context [{:keys [db]}]
  (fn [{:keys [selected-context]} idx]
    (let [selected-context (datastore/remove-stored-context db selected-context idx)]
      {:selected-context selected-context})))

(defn cycle-search-mode [{:keys [db]}]
  (fn [{:keys [selected-context] :as opts}]
    (let [selected-context (datastore/cycle-search-mode db selected-context)]
      {:selected-context selected-context
       :issues           (search/search-issues db (assoc opts :selected-context selected-context))})))

(defn delete-issue [{:keys [db]}]
  (fn [opts issue]
    (deletion/delete-item db issue)
    {:issues         (search/search-issues db opts)
     :issue-view? false}))

(defn delete-context [{:keys [db]}]
  (fn [opts arg]
    (deletion/delete-item db arg)
    {:issues           (search/search-issues db opts)
     :contexts         (search/search-contexts db "")
     :selected-context nil
     :issue-view? false}))

(defn- get-selected-secondary-contexts-set 
  [{{{{{:keys [selected-secondary-contexts
               secondary-contexts-inverted]} :current} :views} :data} :selected-context}]
  (into #{}  (when-not 
               secondary-contexts-inverted
               selected-secondary-contexts)))

(defn fetch-aggregated-contexts [{:keys [db]}]
  (fn [state]
    (when-not (:selected-context state) (throw (Exception. "fetch-aggregated-contexts called without selected-context")))
    (search/fetch-aggregated-contexts 
     db (assoc (make-search-issues state) 
               :only-context-aggregation? true))))

(defn insert-issue [{:keys [db]}]
  (fn [{:keys [selected-context]
        :as state} 
       {:keys [title]}
       alternative-behaviour?]
    (log/info "insert-issue")
    (let [item (insertion/insert-issue db 
                                       title
                                       selected-context 
                                       (get-selected-secondary-contexts-set state)
                                       alternative-behaviour?)]
      (log/info {:item (select-keys item [:id :title])} "Inserted item")
      (datastore.relations/set-collection-titles-of-new-issue db (:id item))
      {:issues         (search/search-issues
                        db
                        (dissoc state :q))
       :q              nil
       :aggregated-contexts ((fetch-aggregated-contexts {:db db}) state)})))

(defn fetch-issue-description [{:keys [db]}]
  (fn [state issue]
    (let [item (datastore/get-item db issue)]
     (assoc state 
            :issue-description (:description item)
            :ignore-issue-description (or (nil? (:description item)) (not (seq (:description item))))))))

(defn link-selected-context-to-context "link selected context as an item to a container"
  [{:keys [db]}]
  (fn 
    [{:keys [selected-issue selected-context] :as opts} arg shift-pressed? _alt-pressed?]
    (let [selected-issue (or selected-issue
                             (datastore/get-item db selected-context))]
      (log/info (str "repository/link-selected-context-to-context " selected-issue " - " shift-pressed?))
      (datastore/reprioritize-context db arg)
      (datastore.relations/link-item-to-container! db selected-issue arg (not shift-pressed?))
      (let [fresh-selected-context (datastore/get-item db selected-context)]
        (merge {:link-context   nil
                :active-search  nil
                :selected-context fresh-selected-context
                :issues         (search/search-issues db (dissoc opts :q))
                :q              nil}
               (when selected-context
                 {:aggregated-contexts ((fetch-aggregated-contexts {:db db}) opts)}))))))

(defn search-contexts [db opts]
  {:contexts (search/search-contexts db opts)})

(defn start-linking-selected-issue-to-context-with-local-search [db opts]
  {:contexts (search/search-contexts db (assoc opts 
                                               :q "" 
                                               :link-context true))
   :q ""
   :link-context true
   :active-search :contexts})

(defn- start-context-search [db opts]
  {:contexts (search/search-contexts db (assoc opts :q ""))
   :q ""
   :active-search :contexts})

(defn start-linking-issue-to-selected-context [db opts]
  {:issues           (search/search-issues db (make-search-issues 
                                               (assoc opts
                                                      :link-issue :context
                                                      :search-globally? true
                                                      :q "")))
   :active-search    :issues
   :search-globally? true
   :link-issue       :context
   :q                ""})

(defn finish-linking-issue [{:keys [db]}]
  (fn [{:keys [selected-context] :as opts} issue-id shift-pressed? alt-pressed?]
    (try
      (log/info (str "finish-linking-issue " shift-pressed? " - " alt-pressed?))
      (datastore/reprioritize-issue db {:id issue-id})
      (let [selected-issue (datastore/get-item db {:id issue-id})] 
        
        (if (and shift-pressed? alt-pressed?)
          (do 
            (datastore.relations/link-item-to-container! db selected-issue selected-context false)
            (datastore.relations/link-item-to-container! db selected-context selected-issue false))
          (datastore.relations/link-item-to-container! db selected-issue selected-context (not shift-pressed?)))

        (let [opts                (-> opts
                                      (dissoc :search-globally? 
                                              :q
                                              :link-issue
                                              :link-context)
                                      (assoc-in [:selected-context 
                                                 :data 
                                                 :views 
                                                 :current 
                                                 :selected-secondary-contexts] []))
              issues              (search/search-issues db opts)
              aggregated-contexts ((fetch-aggregated-contexts {:db db}) opts)
              selected-context (datastore/get-item db selected-context)]
          {:issues              issues
           :active-search       nil
           :selected-context selected-context
           :link-issue          nil
           :link-context        nil
           :search-globally?    false
           :q                   nil
           :aggregated-contexts aggregated-contexts}))
      (catch Exception e
        (log/error (str "Caught an exception in link-issue-to-selected-context " (.getMessage e)))
        (throw e)))))

(defn reprioritize-issue [{:keys [db]}]
  (fn [state issue]
    (log/info (str "repository/reprioritize-issue" (:id issue) (:title issue)))
    (datastore/reprioritize-issue db issue)
    {:issues           (search/search-issues db (dissoc state 
                                                        :search-globally?
                                                        :q))
     :active-search    nil
     :search-globally? false
     :q                nil}))

(defn upgrade-issue-to-context [{:keys [db]}]
  (fn [{:keys [selected-context]}]
    (log/info (str "repository/upgrade-issue-to-context" (:id selected-context)))
    {:selected-context (datastore/upgrade-issue-to-context! db selected-context)}))

(defn unlink-selected-item-from-container [{:keys [db]}]
  (fn [{:keys [selected-context old-selected-context] :as state}]
    (log/info (str "repository/unlink-selected-item-from-container - Try removing " (:id selected-context) ":" (:title selected-context) " from " (:id old-selected-context) ":" (:title old-selected-context)))
    (if (or (not (datastore.relations/unlink-item-from-container! db selected-context old-selected-context))
            (not old-selected-context))
      state
      (do
        (log/info (str "repository/unlink-selected-item-from-container - Removing now"))
        {:selected-context old-selected-context
         :issues (search/search-issues db (assoc state :selected-context old-selected-context))
         :aggregated-contexts ((fetch-aggregated-contexts {:db db}) (assoc state :selected-context old-selected-context))
         :issue-view? false}))))

(defn select-last-context [{:keys [db]}]
  (fn [{:keys [old-selected-context] :as state}]
    (if-not old-selected-context
      state
      (do 
        (log/info (str "repository/select-last-context - " (:id old-selected-context) ":" (:title old-selected-context)))
        {:selected-context    old-selected-context
         :issues              (search/search-issues db (assoc state :selected-context old-selected-context))
         :aggregated-contexts ((fetch-aggregated-contexts {:db db}) (assoc state :selected-context old-selected-context))
         :issue-view?         false}))))

(defn update-item [{:keys [db]}]
  (fn [opts arg]
    (let [context (or (:context (:context arg)) (:context arg))
          issue-contexts (:issue-contexts arg)]
      (log/info (str "repository/update-item" (:id context) (:title context) arg))
      (datastore.relations/set-the-containers-of-item! db context issue-contexts)
      (let [selected-context (datastore/update-item db context)]
        (merge {:selected-context selected-context
                :issues           (search/search-issues db (-> opts
                                                               (dissoc :q)
                                                               (assoc :selected-context selected-context)))
                :q                nil})))))

(defn start-global-search [{:keys [db]}]
  (fn [state]
    {:issues           (search/search-issues
                        db
                        (make-search-issues
                         (assoc state
                                :q ""
                                :active-search    :issues
                                :search-globally? true)))
     :active-search    :issues
     :search-globally? true
     :link-context     false
     :link-issue       nil
     :q                ""}))

(defn list-resources [{:keys [db privacy-mode]}]
  (fn [{:keys                                                             [cmd
                                                                           arg
                                                                           active-search
                                                                           selected-context]
        :as                                                               opts}] 
    (log-opts opts) 
    ;; {:clj-kondo/ignore [:unresolved-var]}
    (merge
     {:cmd                             nil
      :arg                             nil}
     (case cmd
       nil
       (cond (= :issues active-search)
             {:issues (search/search-issues db 
                                            (assoc (make-search-issues opts)
                                                   :skip-context-aggregation? true))}
             (= :contexts active-search) (search-contexts db opts)
             :else
             (merge {:issues   (search/search-issues db opts)
                     :public?  (and @privacy/*public? (= :private privacy-mode))}
                    (when-not (and (not= 0 (:events-view opts)) 
                                   (not selected-context))
                      {:contexts (search/search-contexts db "")})))
       :start-global-search ((start-global-search {:db db}) opts)
       :link-issue-to-selected-context (start-linking-issue-to-selected-context db opts)
       :start-linking-selected-issue-to-context (start-linking-selected-issue-to-context-with-local-search db opts)
       :start-context-search (start-context-search db opts)
       :insert-context
       {:selected-context                        (datastore/new-context db arg)
        :aggregated-contexts                     '()
        :issues                                  []
        :q                                       nil
        :active-search                           :issues
        :unassigned-secondary-contexts-selected? false}
       :update-context-description
       {:selected-context (datastore/update-context-description db arg)}
       :deselect-context
       {:issues           (search/search-issues db (dissoc opts :selected-context :q))
        :contexts         (search/search-contexts db "")
        :selected-context nil
        :q                nil}

         ;; TODO remove :else clause. fix where there are cases where this fires but there shoulnd't be
       :else {}))))

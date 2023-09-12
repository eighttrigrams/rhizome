(ns repository ;; data-driven logic
  (:require [clojure.string :as str]
            [mount.core :as mount]
            [datastore.config :as config]
            datastore
            [datastore.search :as search]
            [cambium.core :as log]
            [clojure.pprint :as pp]))

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

(defn- log-opts [{:keys [cmd q active-search] :as opts}]
  (log/info (str "list-resources - "
                 (or cmd (str active-search "(" q ")"))
                 " - "
                 (with-out-str (pp/pprint opts)))))

(defn fetch-context [db {:keys [selected-issue]} arg]
  (try
    (let [[arg change-context?] arg
          selected-context (datastore/get-context db arg)
          opts             {:search-globally?                false
                            :selected-context                selected-context}]
      (datastore/reprioritize-context db arg)
      (merge opts
             {:selected-context                        selected-context
              :issues                                  (search/search-issues db (dissoc opts :q))
              :active-search                           (if (or change-context?
                                                               selected-issue) 
                                                         nil :issues)
              :context-to-fetch                        nil
              :unassigned-secondary-contexts-selected? false
              :q                                       nil}))
    (catch Exception e
      (log/error (str "Caught an exception in fetch-context " (.getMessage e)))
      (throw e))))

(defn- change-secondary-contexts-operation [db]
  (fn [opts]
    (let [context (datastore/update-context db {:context (:selected-context opts)})]
      {:selected-context context
       :issues (search/search-issues db (assoc opts :selected-context context))})))

(defn change-secondary-contexts-selection [{:keys [db]}]
  (change-secondary-contexts-operation db))

(defn change-secondary-contexts-unassigned-selected [{:keys [db]}]
  (change-secondary-contexts-operation db))

(defn change-secondary-contexts-inverted [{:keys [db]}]
  (change-secondary-contexts-operation db))

(defn deselect-secondary-contexts [{:keys [db]}]
  (fn [opts]
    (let [context (datastore/update-context db {:context (:selected-context
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
                                                              ))})]
      {:issues                          (search/search-issues
                                         db
                                         (assoc opts :selected-context context))
       :contexts                        (search/search-contexts db "")
       :selected-context context})))

(defn cycle-events-view [{:keys [db]}]
  (fn [{:keys [selected-context] :as opts}]
    (if selected-context
      (let [selected-context (datastore/cycle-events-view db selected-context)]
        {:issues         (search/search-issues db (assoc opts :selected-context selected-context))
         :selected-context selected-context
         :contexts       []
         :selected-issue nil
         :q              nil})
      (let [opts (-> opts
                     (dissoc :q)
                     (update :events-view #(mod (inc (or % 0)) 3)))]
        {:issues                          (search/search-issues db opts)
         :contexts                        (if (= 0 (:events-view opts))
                                            (search/search-contexts db opts)
                                            [])
         :events-view (:events-view opts)
         :selected-issue                  nil
         :q                               nil}))))

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

(defn insert-issue [{:keys [db]}]
  (fn [{:keys [selected-context]
      {{{{:keys [selected-secondary-contexts]} :current} :views} :data} :selected-context
      :as state} 
     issue]
    (try
      (let [_selected-issue (datastore/new-issue db 
                                                 issue
                                                 (:id selected-context)
                                                 (into #{} selected-secondary-contexts))]
        {:selected-issue nil
         :issues         (search/search-issues
                          db
                          (dissoc state :q :selected-issue))
         :q              nil})
      (catch Exception e
        (log/error (str "Caught an exception in insert-issue " (.getMessage e)))))))

(defn- link-issue-to-selected-context 
  "when context selected add an issue"
  [db {:keys [selected-context] :as opts} arg]
  (try
    (let [selected-issue (datastore/get-issue db {:id arg})
          context-ids   (keys (:contexts selected-issue))]
      (datastore/link-issue-contexts db {:id arg} (vec (set (conj context-ids (:id selected-context)))))
      {:selected-issue   nil
       :issues           (search/search-issues db 
                                               (-> opts
                                                   (dissoc :search-globally? 
                                                           :q
                                                           :link-issue
                                                           :link-context)
                                                   (assoc-in [:selected-context 
                                                              :data 
                                                              :views 
                                                              :current 
                                                              :selected-secondary-contexts] [])))
       :active-search    nil
       :link-issue       nil
       :link-context     nil
       :search-globally? false
       :q                nil})
    (catch Exception e
      (log/error (str "Caught an exception in link-issue-to-selected-context " (.getMessage e)))
      (throw e))))

(defn- link-selected-issue-to-context 
  "when issue selected link to yet another context"
  [db {:keys [selected-issue] :as opts} arg]
  (try
    (datastore/link-issue-contexts db selected-issue 
                                   (vec (set (conj (keys (:contexts selected-issue))
                                                   (:id arg)))))
    {:link-context   nil
     :selected-issue (datastore/get-issue db selected-issue)
     :active-search  nil
     :issues         (search/search-issues db (dissoc opts :q))
     :q              nil}
    (catch Exception e 
      (log/error (str "Caught an exception in link-selected-issue-to-context " (.getMessage e)))
      (throw e))))

(defn- link-issue-to-selected-issue [db {:keys [selected-issue] :as opts} arg]
  (try
    (datastore/link-issue db (:id selected-issue) arg)
    {:selected-issue   (datastore/get-issue db selected-issue)
     :issues           (search/search-issues db (-> opts
                                                    (dissoc :search-globally?)
                                                    (assoc-in [:selected-context 
                                                               :data 
                                                               :views
                                                               :current
                                                               :selected-secondary-contexts] [])))
     :active-search    nil
     :link-issue       nil
     :search-globally? false
     :q                nil}
    (catch Exception e
      (log/error (str "Caught an exception in link-issue-to-selected-issue " (.getMessage e)))
      (throw e))))

(defn update-context [db opts arg]
  {:selected-context (datastore/update-context db arg)
   :issues           (search/search-issues db (dissoc opts :q))
   :q                nil})

(defn search-contexts [db opts]
  {:contexts (search/search-contexts db opts)})

(defn make-search-issues
  [{:keys [link-issue
           search-globally?]
    :as   opts}]
  #(if (or (= :issue link-issue)
           search-globally?)
     (-> opts
         (cond-> :selected-context
           (update :selected-context (fn [a] (dissoc a :search_mode))))
         (assoc-in [:selected-context 
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
                    :secondary-contexts-unassigned-selected] false))
     opts))

(defn start-linking-selected-issue-to-issue-with-local-search [db search-issues]
  {:issues           (search/search-issues db (assoc (search-issues) 
                                                     :link-issue :issue
                                                     :search-globally? false
                                                     :q ""))
   :active-search    :issues
   :search-globally? false
   :link-issue       :issue
   :q                ""})

(defn start-linking-selected-issue-to-issue-with-global-search [db search-issues]
  {:issues           (search/search-issues db (assoc (search-issues)
                                                     :link-issue :issue
                                                     :search-globally? true
                                                     :q ""))
   :active-search    :issues
   :search-globally? true
   :link-issue       :issue
   :q                ""})

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

(defn start-linking-issue-to-selected-context [db search-issues]
  {:issues           (search/search-issues db (assoc (search-issues)
                                                     :link-issue :context
                                                     :search-globally? true
                                                     :q ""))
   :active-search    :issues
   :search-globally? true
   :link-issue       :context
   :q                ""})

(defn finish-linking-issue [{:keys [db]}]
  (fn [{:keys [link-issue] :as opts} arg]
    (cond (= :issue link-issue)
          (link-issue-to-selected-issue db opts arg)
          (= :context link-issue)
          (link-issue-to-selected-context db opts arg))))

(defn select-issue [{:keys [db]}]
  (fn [state issue skip-select?]
    (datastore/reprioritize-issue db issue)
    {:selected-issue   (when-not skip-select? (datastore/get-issue db issue))
     :issues           (search/search-issues db (dissoc state 
                                                        :search-globally?
                                                        :q
                                                        :selected-issue))
     :active-search    nil
     :search-globally? false
     :q                nil}))

(defn update-issue [db {:keys [selected-issue] :as opts} arg]
  (datastore/link-issue-contexts db selected-issue (:issue-contexts arg))
  {:selected-issue (when-not (:deselect-issue? arg)
                     (datastore/update-issue db (:issue arg)))
   :issues         (search/search-issues db (dissoc opts :q))
   :q              nil})

(defn split-issue [db {{selected-context-id :id} :selected-context :as opts} arg]
  (let [issue arg 
        secondary-contexts-ids-set (set (keys (dissoc (:contexts issue) selected-context-id)))
        titles (reverse (str/split (:description issue) #"\n\n"))]
    (try
      (doall (for [title titles]
               (do
                 (Thread/sleep 10)
                 (datastore/new-issue db
                                      {:title title} 
                                      selected-context-id 
                                      secondary-contexts-ids-set))))
      (datastore/delete-issue db issue)
      (catch Exception e 
        (log/error (str "Caught an split-issue " (.getMessage e)))))
    {:issues (search/search-issues db (dissoc opts :q))
     :selected-issue nil
     :active-search :issues}))

(defn start-global-search [{:keys [db]}]
  (fn [state]
    {:issues           (search/search-issues
                        db
                        ((make-search-issues
                          (assoc state
                                 :q ""
                                 :active-search    :issues
                                 :search-globally? true))))
     :active-search    :issues
     :search-globally? true
     :link-context     false
     :link-issue       nil
     :q                ""}))

(defn list-resources [{:keys [db]}]
  (fn [{:keys                                                             [cmd
                                                                           arg
                                                                           active-search
                                                                           selected-context]
        :as                                                               opts}]

    (log-opts opts)

    (try
      #_{:clj-kondo/ignore [:unresolved-var]}
      (merge
       {:cmd                             nil
        :arg                             nil}
       (let [search-issues (make-search-issues opts)]
         (case cmd
           nil
           (cond (= :issues active-search)
                 {:issues (search/search-issues db (search-issues))}
                 (= :contexts active-search) (search-contexts db opts)
                 :else
                 {:issues   (search/search-issues db opts)
                  :contexts (search/search-contexts db "")})
           :start-global-search ((start-global-search {:db db}) opts)
           :link-with-global-search (start-linking-selected-issue-to-issue-with-global-search db search-issues)
           :link-with-local-search (start-linking-selected-issue-to-issue-with-local-search db search-issues)
           :link-issue-to-selected-context (start-linking-issue-to-selected-context db search-issues)
           :start-linking-selected-issue-to-context (start-linking-selected-issue-to-context-with-local-search db opts)
           :start-context-search (start-context-search db opts)
           :split-issue (split-issue db opts arg)
           :delete-issue
           (do (datastore/delete-issue db arg)
               {:issues         (search/search-issues db opts)
                :selected-issue nil})
           :delete-context
           (do (datastore/delete-context db arg)
               {:issues           (search/search-issues db opts)
                :contexts         (search/search-contexts db "")
                :selected-context nil})
           :link-context (link-selected-issue-to-context db opts arg)
           :insert-context
           {:selected-context                        (datastore/new-context db arg)
            :selected-issue                          nil
            :issues                                  []
            :q                                       nil
            :active-search                           :issues
            :unassigned-secondary-contexts-selected? false}
           :update-issue-description
           {:selected-issue (datastore/update-issue-description db arg)
            :issues         (search/search-issues db (dissoc opts :q))
            :q              nil}
           :update-context-description
           {:selected-context (datastore/update-context-description db arg)}
           :update-issue (update-issue db opts arg)
           :update-context (update-context db opts arg)
           :fetch-context (fetch-context db opts arg)
           :deselect-context
           {:issues           (search/search-issues db (dissoc opts :selected-context :q))
            :contexts         (search/search-contexts db "")
            :selected-context nil
            :q                nil}

         ;; TODO remove :else clause. fix where there are cases where this fires but there shoulnd't be
           :else {})))
      (catch Exception e
        (log/error (str "Caught an exception in list-resources(mainfn): " (.getMessage e)))))))

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
              :active-search                           (if-not change-context? :issues nil)
              :context-to-fetch                        nil
              :selected-issue                          (if-not change-context? nil selected-issue)
              :secondary-contexts-inverted?            false
              :secondary-contexts-and?                 false
              :unassigned-secondary-contexts-selected? false
              :q                                       nil}))
    (catch Exception e
      (log/error (str "Caught an exception in fetch-context " (.getMessage e)))
      (throw e))))

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
                                                   (assoc-in [:selected-context :data :selected-secondary-contexts] [])))
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
                                                    (assoc-in [:selected-context :data :selected-secondary-contexts] [])))
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
         (assoc-in [:selected-context :data :selected-secondary-contexts] [])
         (dissoc :show-events?
                 :unassigned-secondary-contexts-selected?
                 :secondary-contexts-inverted?))
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

(defn finish-linking-issue [db {:keys [link-issue] :as opts} arg]
  (cond (= :issue link-issue)
        (link-issue-to-selected-issue db opts arg)
        (= :context link-issue)
        (link-issue-to-selected-context db opts arg)))

(defn fetch-issue [db opts arg]
  (let [[issue skip-select?] arg]
    (datastore/reprioritize-issue db issue)
    {:selected-issue   (when-not skip-select? (datastore/get-issue db issue))
     :issues           (search/search-issues db (dissoc opts :search-globally? :q))
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

(defn list-resources [{:keys [cmd
                              arg
                              active-search
                              selected-context] 
                       {{:keys [selected-secondary-contexts]} :data} :selected-context
                       :as   opts} db]
  
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
         :start-global-search
         {:issues           (search/search-issues db (search-issues))
          :active-search    :issues
          :search-globally? true
          :link-context     false
          :link-issue       nil
          :q                ""}
         :link-with-global-search (start-linking-selected-issue-to-issue-with-global-search db search-issues)
         :link-with-local-search (start-linking-selected-issue-to-issue-with-local-search db search-issues)
         :finish-link-issue (finish-linking-issue db opts arg)
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
         :cycle-search-mode
         (let [selected-context (datastore/cycle-search-mode db selected-context)]
           {:selected-context selected-context
            :issues           (search/search-issues db (assoc opts :selected-context selected-context))})
         :link-context (link-selected-issue-to-context db opts arg)
         :insert-issue
         (let [selected-issue (datastore/new-issue db arg
                                                   (:id selected-context)
                                                   (into #{} selected-secondary-contexts))]
           {:selected-issue nil
            :issues         (search/search-issues db (dissoc (assoc opts :selected-issue selected-issue) :q))
            :q              nil})
         :insert-context
         {:selected-context                        (datastore/new-context db arg)
          :selected-issue                          nil
          :issues                                  []
          :q                                       nil
          :active-search                           :issues
          :secondary-contexts-inverted?            false
          :secondary-contexts-and?                 false
          :unassigned-secondary-contexts-selected? false}
         :update-issue-description
         {:selected-issue (datastore/update-issue-description db arg)
          :issues         (search/search-issues db (dissoc opts :q))
          :q              nil}
         :update-context-description
         {:selected-context (datastore/update-context-description db arg)}
         :update-issue (update-issue db opts arg)
         :update-context (update-context db opts arg)
         :fetch-issue (fetch-issue db opts arg)
         :fetch-context (fetch-context db opts arg)
         :change-secondary-contexts-selection
         (let [context (datastore/update-context db {:context (:selected-context opts)})]
           {:selected-context context
            :issues (search/search-issues db (assoc opts :selected-context context))})
         :change-secondary-contexts-unassigned-selected
         {:issues (search/search-issues db opts)}
         :change-secondary-contexts-inverted
         {:issues (search/search-issues db opts)}
         :change-secondary-contexts-and
         {:issues (search/search-issues db opts)}
         :deselect-secondary-contexts
         (let [context (datastore/update-context db {:context (:selected-context 
                                                               (assoc-in opts 
                                                                         [:selected-context :data :selected-secondary-contexts] 
                                                                         []))})]
           {:issues                          (search/search-issues 
                                              db 
                                              (assoc opts :selected-context context))
            :contexts                        (search/search-contexts db "")
            :selected-context context})
         :exit-events-view
         (if selected-context
           {:show-events? false
            :issues       (search/search-issues db (dissoc (assoc opts :show-events? false) :q))
            :q            nil}
           {:issues         (search/search-issues db (dissoc (assoc opts :show-events? false) :q))
            :contexts       (search/search-contexts db "")
            :selected-issue nil
            :show-events?   false
            :q              nil})
         :enter-events-view
         {:issues                          (search/search-issues db (assoc opts :show-events? true :q nil))
          :contexts                        []
          :selected-issue                  nil
          :show-events?                    true
          :q                               nil}
         :deselect-context
         {:issues           (search/search-issues db (dissoc opts :selected-context :q))
          :contexts         (search/search-contexts db "")
          :selected-context nil
          :q                nil}

         ;; TODO remove :else clause. fix where there are cases where this fires but there shoulnd't be
         :else {})))
    (catch Exception e 
      (log/error (str "Caught an exception in list-resources(mainfn): " (.getMessage e))))))

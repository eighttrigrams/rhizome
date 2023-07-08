(ns repository ;; data-driven logic
  (:require [mount.core :as mount]
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

(defn list-resources [{:keys [q 
                              cmd
                              arg
                              active-search
                              selected-issue
                              link-issue
                              search-globally?
                              selected-context
                              selected-secondary-contexts-ids] 
                       :as   opts} db]
  
  (log-opts opts)

  (try 
    #_{:clj-kondo/ignore [:unresolved-var]}
    (merge 
     {:cmd                             nil
      :arg                             nil}
     (let [search-issues #(if (or (= :issue link-issue)
                                  search-globally?)
                            (-> opts
                                (cond-> :selected-context
                                  (update :selected-context (fn [a] (dissoc a :search_mode))))
                                (assoc :selected-secondary-contexts-ids '())
                                (dissoc :show-events?
                                        :unassigned-secondary-contexts-selected?
                                        :secondary-contexts-inverted?))
                            opts)]
       (case cmd
         nil
         (cond (= :issues active-search)
               {:issues (search/search-issues db (search-issues))}
               (= :contexts active-search)
               {:contexts (search/search-contexts db q)}
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
         :link-with-global-search
         {:issues           (search/search-issues db (search-issues))
          :active-search    :issues
          :search-globally? true
          :link-issue       :issue
          :q                ""}
         :link-with-local-search
         {:issues           (search/search-issues db (search-issues))
          :active-search    :issues
          :search-globally? false
          :link-issue       :issue
          :q                ""}
         :link-context-with-global-search
         {:issues           (search/search-issues db (search-issues))
          :active-search    :issues
          :search-globally? true
          :link-issue       :context
          :q                ""}
         :start-context-search
         {:contexts (search/search-contexts db "")}
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
         :link-issues
         (do
           (datastore/link-issue db (:id selected-issue) arg)
           {:selected-issue   (datastore/get-issue db selected-issue)
            :issues           (search/search-issues db (-> opts
                                                           (dissoc :search-globally?)
                                                           (assoc :selected-secondary-contexts-ids #{})))
            :active-search    nil
            :link-issue       nil
            :search-globally? false
            :q                nil})
         :link-issue-context ;; when context selected, add an issue
         (let [selected-issue (datastore/get-issue db {:id arg})
               context-ids   (keys (:contexts selected-issue))]
           (datastore/link-issue-contexts db {:id arg} (vec (set (conj context-ids (:id selected-context)))))
           {:selected-issue   nil
            :issues           (search/search-issues db (-> opts
                                                           (dissoc :search-globally? :q)
                                                           (assoc :selected-secondary-contexts-ids #{})))
            :active-search    nil
            :link-issue       nil
            :search-globally? false
            :q                nil})
         :link-context ;; when issue selected, link to yet another context
         (do
           (datastore/link-issue-contexts db selected-issue (vec (set (conj (keys (:contexts selected-issue))
                                                                            (:id arg)))))
           {:link-context   nil
            :selected-issue (datastore/get-issue db selected-issue)
            :active-search  nil
            :issues         (search/search-issues db (dissoc opts :q))
            :q              nil})
         :insert-issue
         (let [selected-issue (datastore/new-issue db arg
                                                   (:id selected-context)
                                                   selected-secondary-contexts-ids)]
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
         :update-issue
         (do
           (datastore/link-issue-contexts db selected-issue (:issue-contexts arg))
           {:selected-issue (datastore/update-issue db (:issue arg))
            :issues         (search/search-issues db (dissoc opts :q))
            :q              nil})
         :update-context
         {:selected-context (datastore/update-context db arg)
          :issues           (search/search-issues db (dissoc opts :q))
          :q                nil}
         :fetch-issue
         (let [[issue skip-select?] arg]
           (datastore/reprioritize-issue db issue)
           {:selected-issue   (when-not skip-select? (datastore/get-issue db issue))
            :issues           (search/search-issues db (dissoc opts :search-globally? :q))
            :active-search    nil
            :search-globally? false
            :q                nil})
         :fetch-context
         (let [[arg change-context?] arg
               selected-context (datastore/get-context db arg)
               opts             {:search-globally?                false
                                 :selected-secondary-contexts-ids #{}
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
         :change-secondary-contexts-selection
         {:issues (search/search-issues db opts)}
         :change-secondary-contexts-unassigned-selected
         {:issues (search/search-issues db opts)}
         :change-secondary-contexts-inverted
         {:issues (search/search-issues db opts)}
         :change-secondary-contexts-and
         {:issues (search/search-issues db opts)}
         :deselect-secondary-contexts
         {:issues                          (search/search-issues db (assoc opts :selected-secondary-contexts-ids #{}))
          :contexts                        (search/search-contexts db "")
          :selected-secondary-contexts-ids #{}}
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
          :selected-secondary-contexts-ids #{}
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
      (log/error (str "Caught and exception in list-resources: " (.getMessage e))))))

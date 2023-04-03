(ns repository ;; data-driven logic
  (:require [mount.core :as mount]
            [datastore.config :as config]
            datastore
            [datastore.search :as search]))

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
(defn get-issues [q]
  (let [db (:db config/config)]
    (search/search-issues db {:q q})))

#_{:clj-kondo/ignore [:unresolved-var]}
(defn get-contexts [q]
  (let [db (:db config/config)]
    (search/search-contexts db q)))

(defn list-resources [{:keys [q 
                              cmd
                              arg
                              active-search
                              link-issue?
                              selected-issue
                              selected-context
                              selected-secondary-contexts-ids] 
                       :as   opts}]

  #_{:clj-kondo/ignore [:unresolved-var]}
  (merge 
   {:cmd                             nil
    :arg                             nil}
   (let [db (:db config/config)]
     (if-not cmd
       (cond (= :issues active-search)
             {:issues (search/search-issues db (cond-> opts
                                                 link-issue?
                                                 (assoc :selected-secondary-contexts-ids '())))}
             (= :contexts active-search)
             {:contexts (search/search-contexts db q)}
             :else 
             {:issues   (search/search-issues db opts)
              :contexts (search/search-contexts db "")})
       (case cmd
         :link-issue-contexts
         {:selected-issue      (datastore/link-issue-contexts db selected-issue arg)
          :issues              (search/search-issues db opts)
          :link-issue-contexts nil}
         :reprioritize-issue 
         (do (datastore/reprioritize-issue db selected-issue)
             {:issues (search/search-issues db opts)})
         :mark-issue-important 
         {:selected-issue (datastore/mark-issue-important db selected-issue)
          :issues         (search/search-issues db opts)}
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
         :link-issue
         (do
           (datastore/link-issue db (:id selected-issue) arg)
           {:selected-issue   (datastore/get-issue db selected-issue)
            :issues           (search/search-issues db (-> opts
                                                           (dissoc :search-globally?)
                                                           (assoc :selected-secondary-contexts-ids #{})))
            :active-search    nil
            :link-issue?      nil
            :search-globally? false})
         :insert-issue 
         (let [selected-issue (datastore/new-issue db arg
                                                   (:id selected-context)
                                                   selected-secondary-contexts-ids)]
           {:selected-issue selected-issue
            :issues         (search/search-issues db (assoc opts :selected-issue selected-issue))})
         :insert-context
         {:selected-context (datastore/new-context db arg)
          :issues           []}
         :update-issue-description
         {:selected-issue (datastore/update-issue-description db arg)
          :issues         (search/search-issues db opts)}
         :update-context-description
         {:selected-context (datastore/update-context-description db arg)}
         :update-issue
         {:selected-issue (datastore/update-issue db arg)
          :issues         (search/search-issues db opts)}
         :update-context
         {:selected-context (datastore/update-context db arg)
          :issues           (search/search-issues db opts)}
         :fetch-issue
         {:selected-issue   (datastore/get-issue db arg)
          :issues           (when active-search (search/search-issues db (dissoc opts :search-globally?)))
          :active-search    nil
          :search-globally? false}
         :fetch-context
         (let [selected-context (datastore/get-context db arg)]
           {:selected-context                        selected-context
            :issues                                  (search/search-issues db (-> opts
                                                                                  (assoc :selected-context selected-context)
                                                                                  (dissoc :search-globally?)))
            :active-search                           nil
            :search-globally?                        false
            :context-to-fetch                        nil
            :secondary-contexts-inverted?            false
            :selected-secondary-contexts-ids         #{}
            :unassigned-secondary-contexts-selected? false})
         :change-secondary-contexts-selection
         {:issues (search/search-issues db opts)}
         :change-secondary-contexts-unassigned-selected
         {:issues (search/search-issues db opts)}
         :change-secondary-contexts-inverted
         {:issues (search/search-issues db opts)}
         :deselect-secondary-contexts
         {:issues                          (search/search-issues db (assoc opts :selected-secondary-contexts-ids #{}))
          :contexts                        (search/search-contexts db "")
          :selected-secondary-contexts-ids #{}}
         :exit-events-view
         {:issues         (search/search-issues db opts)
          :contexts       (search/search-contexts db "")
          :selected-issue nil}
         :enter-events-view
         {:issues                          (search/search-issues db opts)
          :contexts                        []
          :selected-issue                  nil
          :selected-context                nil
          :selected-secondary-contexts-ids #{}}
         :deselect-context
         {:issues           (search/search-issues db (dissoc opts :selected-context))
          :contexts         (search/search-contexts db "")
          :selected-context nil})))))

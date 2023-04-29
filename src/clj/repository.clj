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
(defn get-contexts [q]
  (let [db (:db config/config)]
    (search/search-contexts db q)))

(defn list-resources [{:keys [q 
                              cmd
                              arg
                              active-search
                              selected-issue
                              link-issue
                              search-globally?
                              selected-context
                              selected-secondary-contexts-ids] 
                       :as   opts}]

  #_{:clj-kondo/ignore [:unresolved-var]}
  (merge 
   {:cmd                             nil
    :arg                             nil}
   (let [db (:db config/config)]
     (case cmd
       nil
       (cond (= :issues active-search)
             {:issues (search/search-issues db (if (or (= :issue link-issue)
                                                       search-globally?)
                                                 (if (and (= :issue link-issue)
                                                          search-globally?)
                                                   (-> opts
                                                       (cond-> :selected-context
                                                         (update :selected-context #(dissoc % :search_mode)))
                                                       (assoc :selected-secondary-contexts-ids '())
                                                       (dissoc :show-events?
                                                               :unassigned-secondary-contexts-selected?
                                                               :secondary-contexts-inverted?))
                                                   (assoc opts :selected-secondary-contexts-ids '()))
                                                 opts))}
             (= :contexts active-search)
             {:contexts (search/search-contexts db q)}
             :else
             {:issues   (search/search-issues db opts)
              :contexts (search/search-contexts db "")})
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
          :search-globally? false})
       :link-issue-context ;; when context selected, add an issue
       (let [selected-issue (datastore/get-issue db {:id arg})
             context-ids   (keys (:contexts selected-issue))]
         (datastore/link-issue-contexts db {:id arg} (vec (set (conj context-ids (:id selected-context)))))
         {:selected-issue   nil
          :issues           (search/search-issues db (-> opts
                                                         (dissoc :search-globally?)
                                                         (assoc :selected-secondary-contexts-ids #{})))
          :active-search    nil
          :link-issue       nil
          :search-globally? false})
       :link-context ;; when issue selected, link to yet another context
       (do 
         (datastore/link-issue-contexts db selected-issue (vec (set (conj (keys (:contexts selected-issue))
                                                                          (:id arg)))))
         {:link-context nil
          :selected-issue (datastore/get-issue db selected-issue)
          :active-search nil
          :issues (search/search-issues db opts)})
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
       (do
         (datastore/link-issue-contexts db selected-issue (:issue-contexts arg))
         {:selected-issue (datastore/update-issue db (:issue arg))
          :issues         (search/search-issues db opts)})
       :update-context
       {:selected-context (datastore/update-context db arg)
        :issues           (search/search-issues db opts)}
       :fetch-issue
       (do
         (datastore/reprioritize-issue db arg)
         {:selected-issue   (datastore/get-issue db arg)
          :issues           (search/search-issues db (dissoc opts :search-globally?))
          :active-search    nil
          :search-globally? false})
       :fetch-context
       (do
         (datastore/reprioritize-context db arg)
         (let [selected-context (datastore/get-context db arg)
               opts             {:search-globally?                false
                                 :selected-secondary-contexts-ids #{}
                                 :selected-context                selected-context}]
           (merge opts
                  {:selected-context                        selected-context
                   :issues                                  (search/search-issues db opts)
                   :active-search                           nil
                   :context-to-fetch                        nil
                   :secondary-contexts-inverted?            false
                   :secondary-contexts-and?                 false
                   :unassigned-secondary-contexts-selected? false})))
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
          :issues         (search/search-issues db (assoc opts :show-events? false))}
         {:issues         (search/search-issues db (assoc opts :show-events? false))
          :contexts       (search/search-contexts db "")
          :selected-issue nil
          :show-events?   false})
       :enter-events-view
       {:issues                          (search/search-issues db (assoc opts :show-events? true))
        :contexts                        []
        :selected-issue                  nil
        :selected-secondary-contexts-ids #{}
        :show-events?                    true}
       :deselect-context
       {:issues           (search/search-issues db (dissoc opts :selected-context))
        :contexts         (search/search-contexts db "")
        :selected-context nil}))))

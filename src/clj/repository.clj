(ns repository ;; TODO rename to controller or something, because it contains (data-driven) logic
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

(defn get-issues [q]
  (let [db (:db config/config)]
    (search/search-issues db {:q q})))

(defn get-contexts [q]
  (let [db (:db config/config)]
    (search/search-contexts db q)))

(defn list-resources [{:keys [q 
                              cmd
                              arg
                              active-search
                              selected-issue
                              selected-context
                              selected-secondary-contexts-ids
                              context-to-fetch
                              link-issue-contexts] 
                       :as   opts}]

  #_{:clj-kondo/ignore [:unresolved-var]}
  (merge 
   {:active-search                   active-search
    :selected-secondary-contexts-ids selected-secondary-contexts-ids
    :selected-issue                  selected-issue
    :selected-context                selected-context}
   (let [db (:db config/config)]
     (cond
       link-issue-contexts
       {:selected-issue (datastore/link-issue-contexts db selected-issue link-issue-contexts)
        :issues         (search/search-issues db opts)}
       (= :reprioritize-issue cmd)
       (do (datastore/reprioritize-issue db selected-issue)
           {:issues (search/search-issues db opts)})
       (= :mark-issue-important cmd)
       {:selected-issue (datastore/mark-issue-important db selected-issue)
        :issues         (search/search-issues db opts)}
       (= :delete-issue cmd)
       (do (datastore/delete-issue db arg)
           {:issues         (search/search-issues db opts)
            :selected-issue nil})
       (= :delete-context cmd)
       (do (datastore/delete-context db arg)
           {:issues           (search/search-issues db opts)
            :contexts         (search/search-contexts db "")
            :selected-context nil})
       (= :cycle-search-mode cmd)
       (let [selected-context (datastore/cycle-search-mode db selected-context)]
         {:selected-context selected-context
          :issues           (search/search-issues db (assoc opts :selected-context selected-context))})
       (= :insert-issue cmd)
       (let [selected-issue (datastore/new-issue db arg
                                                 (:id selected-context)
                                                 selected-secondary-contexts-ids)]
         {:selected-issue selected-issue
          :issues         (search/search-issues db (assoc opts :selected-issue selected-issue))})
       (= :insert-context cmd)
       {:selected-context (datastore/new-context db arg)
        :issues           []}
       (= :update-issue-description cmd)
       {:selected-issue (datastore/update-issue-description db arg)
        :issues         (search/search-issues db opts)}
       (= :update-context-description cmd)
       {:selected-context (datastore/update-context-description db arg)}
       (= :update-issue cmd)
       {:selected-issue (datastore/update-issue db arg)
        :issues         (search/search-issues db opts)}
       (= :update-context cmd)
       {:selected-context (datastore/update-context db arg)
        :issues           (search/search-issues db opts)} ;; maybe not necessary (yet)
       (= :fetch-issue cmd)
       {:selected-issue   (datastore/get-issue db arg)
        :issues           (when active-search (search/search-issues db (dissoc opts :search-globally?)))
        :active-search    nil
        :search-globally? false}
       context-to-fetch
       (let [selected-context (datastore/get-context db context-to-fetch)]
         {:selected-context selected-context
          :issues           (search/search-issues db (-> opts
                                                         (assoc :selected-context selected-context)
                                                         (dissoc :search-globally?)))
          :active-search    nil
          :search-globally? false})
       (= :issues active-search)
       {:issues (search/search-issues db opts)}
       (= :contexts active-search)
       {:contexts (search/search-contexts db q)}
       (= :change-secondary-contexts-selection cmd)
       {:issues (search/search-issues db opts)}
       (= :change-secondary-contexts-unassigned-selected cmd)
       {:issues (search/search-issues db opts)}
       (= :change-secondary-contexts-inverted cmd)
       {:issues (search/search-issues db opts)}
       (= :deselect-secondary-contexts cmd)
       {:issues                          (search/search-issues db (assoc opts :selected-secondary-contexts-ids #{}))
        :contexts                        (search/search-contexts db "")
        :selected-secondary-contexts-ids #{}}
       (= :exit-events-view cmd)
       {:issues         (search/search-issues db opts)
        :contexts       (search/search-contexts db "")
        :selected-issue nil}
       (= :enter-events-view cmd)
       {:issues                          (search/search-issues db opts)
        :contexts                        []
        :selected-issue                  nil
        :selectec-context                nil
        :selected-secondary-contexts-ids #{}}
       (= :deselect-context cmd)
       {:issues           (search/search-issues db (dissoc opts :selected-context))
        :contexts         (search/search-contexts db "")
        :selected-context nil}
       ;; TODO get rid of default case here
       :else {:issues   (search/search-issues db opts)
              :contexts (search/search-contexts db "")}))))

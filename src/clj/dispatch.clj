(ns dispatch
  (:require [net.eighttrigrams.defn-over-http.core :refer [defdispatch]]
            [cambium.core :as log]
            [datastore.config :as config]
            [repository :refer [list-resources 
                                insert-issue
                                insert-context
                                change-secondary-contexts-selection
                                change-secondary-contexts-unassigned-selected
                                change-secondary-contexts-inverted
                                deselect-secondary-contexts
                                finish-linking-issue
                                reprioritize-issue
                                cycle-search-mode
                                store-current-view
                                load-stored-context
                                remove-stored-context
                                update-item
                                unlink-item
                                unlink-selected-item-from-container
                                delete-item
                                upgrade-issue-to-context
                                link-selected-context-to-context
                                select-last-context
                                fetch-context
                                deselect-context
                                fetch-aggregated-contexts
                                delete-context
                                fetch-issue-description]]))

(defn- handle-error [e]
  (log/error {:error-handler :handle-error} e "an error occured"))

(defdispatch handler 
  {:error-handler handle-error
   :pass-server-args? true}
  list-resources 
  insert-issue
  insert-context
  change-secondary-contexts-selection
  change-secondary-contexts-unassigned-selected
  change-secondary-contexts-inverted
  deselect-secondary-contexts
  finish-linking-issue
  reprioritize-issue
  cycle-search-mode
  store-current-view
  load-stored-context
  remove-stored-context
  delete-item
  unlink-item
  unlink-selected-item-from-container
  update-item
  fetch-aggregated-contexts
  select-last-context
  upgrade-issue-to-context
  link-selected-context-to-context
  fetch-context
  deselect-context
  delete-context
  fetch-issue-description)

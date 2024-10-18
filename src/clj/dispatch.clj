(ns dispatch
  (:require [net.eighttrigrams.defn-over-http.core :refer [defdispatch-with-args]]
            [datastore.config :as config]
            [repository :refer [list-resources 
                                insert-issue
                                change-secondary-contexts-selection
                                change-secondary-contexts-unassigned-selected
                                change-secondary-contexts-inverted
                                deselect-secondary-contexts
                                finish-linking-issue
                                reprioritize-issue
                                cycle-search-mode
                                show-events
                                show-past-events
                                deselect-events
                                store-current-view
                                load-stored-context
                                remove-stored-context
                                update-context
                                delete-selected-issue
                                delete-issue
                                upgrade-issue-to-context
                                unlink-selected-item-from-container
                                fetch-context
                                fetch-aggregated-contexts
                                delete-context
                                flip-privacy]]))

(defdispatch-with-args handler 
  list-resources 
  insert-issue
  change-secondary-contexts-selection
  change-secondary-contexts-unassigned-selected
  change-secondary-contexts-inverted
  deselect-secondary-contexts
  finish-linking-issue
  reprioritize-issue
  cycle-search-mode
  show-events
  show-past-events
  deselect-events
  store-current-view
  load-stored-context
  remove-stored-context
  delete-selected-issue
  delete-issue 
  update-context
  fetch-aggregated-contexts
  unlink-selected-item-from-container
  upgrade-issue-to-context
  fetch-context
  delete-context
  flip-privacy)

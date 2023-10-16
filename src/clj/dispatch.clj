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
                                select-issue
                                cycle-search-mode
                                show-events
                                show-past-events
                                deselect-events
                                cycle-notes-mode
                                store-current-view
                                load-stored-context
                                remove-stored-context
                                update-issue
                                delete-selected-issue
                                delete-issue
                                upgrade-issue-to-context
                                unlink-selected-item-from-container
                                fetch-context
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
  select-issue
  cycle-search-mode
  show-events
  show-past-events
  deselect-events
  cycle-notes-mode
  store-current-view
  load-stored-context
  remove-stored-context
  delete-selected-issue
  delete-issue 
  update-issue
  unlink-selected-item-from-container
  upgrade-issue-to-context
  fetch-context
  delete-context
  flip-privacy)

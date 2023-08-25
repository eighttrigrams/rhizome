(ns dispatch
  (:require [net.eighttrigrams.defn-over-http.core :refer [defdispatch-with-args]]
            [datastore.config :as config]
            [repository :refer [list-resources 
                                insert-issue
                                change-secondary-contexts-selection
                                change-secondary-contexts-unassigned-selected
                                change-secondary-contexts-inverted
                                change-secondary-contexts-and
                                deselect-secondary-contexts
                                finish-linking-issue
                                ]]))

(defdispatch-with-args handler 
  list-resources 
  insert-issue
  change-secondary-contexts-selection
  change-secondary-contexts-unassigned-selected
  change-secondary-contexts-inverted
  change-secondary-contexts-and
  deselect-secondary-contexts
  finish-linking-issue)

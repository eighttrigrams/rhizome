(ns et.rz.hub.dispatch
  (:require [net.eighttrigrams.defn-over-http.core :refer [defdispatch]]
            [cambium.core :as log]
            [et.rz.hub.repository :refer
             [list-resources insert-item insert-context change-secondary-contexts-selection
              change-secondary-contexts-unassigned-selected change-secondary-contexts-inverted
              change-description-filter
              deselect-secondary-contexts finish-linking-item reprioritize-item cycle-search-mode
              store-current-view load-stored-context remove-stored-context update-item unlink-item
              unlink-selected-item-from-container delete-item upgrade-item-to-context
              link-selected-context-to-context select-last-context fetch-context deselect-context
              fetch-aggregated-contexts delete-context fetch-item-description
              fetch-item-provenance update-annotations
              fetch-relation-description fetch-relation-history fetch-relation-provenance
              vector-search-related-items vector-threshold-search-related-items]]
            [et.rz.hub.poll :refer
             [list-youtube-poll-channels add-youtube-poll-channel delete-youtube-poll-channel
              update-youtube-poll-channel
              list-atom-poll-feeds add-atom-poll-feed delete-atom-poll-feed]]))

(defn- handle-error [e] (log/error {:error-handler :handle-error} e "an error occured"))

(defdispatch handler*
             {:error-handler handle-error :pass-server-args? true}
             list-resources
             insert-item
             insert-context
             change-secondary-contexts-selection
             change-secondary-contexts-unassigned-selected
             change-secondary-contexts-inverted
             change-description-filter
             deselect-secondary-contexts
             finish-linking-item
             reprioritize-item
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
             upgrade-item-to-context
             link-selected-context-to-context
             fetch-context
             deselect-context
             delete-context
             fetch-item-description
             fetch-item-provenance
             update-annotations
             fetch-relation-description
             fetch-relation-history
             fetch-relation-provenance
             vector-search-related-items
             vector-threshold-search-related-items
             list-youtube-poll-channels
             add-youtube-poll-channel
             delete-youtube-poll-channel
             update-youtube-poll-channel
             list-atom-poll-feeds
             add-atom-poll-feed
             delete-atom-poll-feed)

;; The `/ui` entry point. `handler*` is what `defdispatch` built above; this
;; name is what `server` and the hub mount.
;;
;; It used to be wrapped: a read-only replica refused every write command here,
;; in band, because the SPA carries queries and mutations through one POST and
;; could not be refused by HTTP method without breaking reading. There are no
;; replicas since step 4 of the architecture rework -- one hub, one mode -- so
;; the classification, the refusal envelope and the per-command guard went with
;; them. What routes a command now is `placement`, and it asks a different
;; question: which machine, not whether.

(def handler handler*)

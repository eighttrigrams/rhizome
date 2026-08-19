(ns api
  (:require-macros [net.eighttrigrams.defn-over-http.core :refer [defn-over-http]])
  (:require ajax.core))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def config {:api-path "/ui" :error-handler #(prn "error caught by base error handler:" %)})

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn-over-http list-resources :return-value {})

(declare insert-item)

(defn-over-http insert-item :return-value {})

(defn-over-http insert-context :return-value {})

(defn-over-http change-secondary-contexts-selection :return-value {})

(defn-over-http change-secondary-contexts-unassigned-selected :return-value {})

(defn-over-http change-secondary-contexts-inverted :return-value {})

(defn-over-http change-description-filter :return-value {})

(defn-over-http deselect-secondary-contexts :return-value {})

(defn-over-http finish-linking-item :return-value {})

(defn-over-http reprioritize-item :return-value {})

(defn-over-http cycle-search-mode :return-value {})

(defn-over-http store-current-view :return-value {})

(defn-over-http load-stored-context :return-value {})

(defn-over-http remove-stored-context :return-value {})

(defn-over-http delete-item :return-value {})

(defn-over-http fetch-context :return-value {})

(defn-over-http deselect-context :return-value {})

(defn-over-http update-item :return-value {})

(defn-over-http unlink-item :return-value {})

(defn-over-http upgrade-item-to-context :return-value {})

(defn-over-http link-selected-context-to-context :return-value {})

(defn-over-http unlink-selected-item-from-container :return-value {})

(defn-over-http select-last-context :return-value {})

(defn-over-http delete-context :return-value {})

(defn-over-http fetch-aggregated-contexts :return-value {})

(defn-over-http fetch-item-description :return-value {})

(defn-over-http fetch-item-provenance :return-value {})

(defn-over-http edit-item-in-obsidian :return-value {})

(defn-over-http sync-obsidian-changes :return-value {})

(defn-over-http discard-obsidian-changes :return-value {})

(defn-over-http get-obsidian-file-content :return-value {})

(defn-over-http update-annotations :return-value {})

(defn-over-http fetch-relation-description :return-value {})

(defn-over-http fetch-relation-history :return-value {})

(defn-over-http fetch-relation-provenance :return-value {})

(defn-over-http vector-search-related-items :return-value {})

(defn-over-http vector-threshold-search-related-items :return-value {})

(defn-over-http list-youtube-poll-channels :return-value {})

(defn-over-http add-youtube-poll-channel :return-value {})

(defn-over-http delete-youtube-poll-channel :return-value {})

(defn-over-http update-youtube-poll-channel :return-value {})

(defn-over-http list-atom-poll-feeds :return-value {})

(defn-over-http add-atom-poll-feed :return-value {})

(defn-over-http delete-atom-poll-feed :return-value {})

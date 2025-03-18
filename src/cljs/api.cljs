(ns api
  (:require-macros [net.eighttrigrams.defn-over-http.core :refer [defn-over-http]])
  (:require ajax.core))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def config {:api-path           "/api"
             :error-handler      #(prn "error caught by base error handler:" %)})

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn-over-http list-resources :return-value {})

(declare insert-issue)

(defn-over-http insert-issue :return-value {})

(defn-over-http change-secondary-contexts-selection :return-value {})

(defn-over-http change-secondary-contexts-unassigned-selected :return-value {})

(defn-over-http change-secondary-contexts-inverted :return-value {})

(defn-over-http deselect-secondary-contexts :return-value {})

(defn-over-http finish-linking-issue :return-value {})

(defn-over-http reprioritize-issue :return-value {})

(defn-over-http cycle-search-mode :return-value {})

(defn-over-http show-past-events :return-value {})

(defn-over-http deselect-events :return-value {})

(defn-over-http store-current-view :return-value {})

(defn-over-http load-stored-context :return-value {})

(defn-over-http remove-stored-context :return-value {})

(defn-over-http delete-issue :return-value {})

(defn-over-http fetch-context :return-value {})

(defn-over-http flip-privacy :return-value {})

(defn-over-http update-item :return-value {})

(defn-over-http upgrade-issue-to-context :return-value {})

(defn-over-http link-selected-context-to-context :return-value {})

(defn-over-http unlink-selected-item-from-container :return-value {})

(defn-over-http select-last-context :return-value {})

(defn-over-http delete-context :return-value {})

(defn-over-http fetch-aggregated-contexts :return-value {})

(defn-over-http fetch-issue-description :return-value {})

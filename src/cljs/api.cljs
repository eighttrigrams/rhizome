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

(defn-over-http select-issue :return-value {})

(defn-over-http cycle-search-mode :return-value {})

(defn-over-http enter-events-view :return-value {})

(defn-over-http exit-events-view :return-value {})

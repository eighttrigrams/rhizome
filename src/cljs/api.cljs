(ns api
  (:require-macros [net.eighttrigrams.defn-over-http.core :refer [defn-over-http]])
  (:require ajax.core))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def config {:api-path           "/api"
             :error-handler      #(prn "error caught by base error handler:" %)})

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn-over-http list-resources :return-value [])

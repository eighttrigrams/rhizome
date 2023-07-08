(ns dispatch
  (:require [net.eighttrigrams.defn-over-http.core :refer [defdispatch]]
            [datastore.config :as config]
            [repository]))

(defn list-resources [opts]
  (repository/list-resources opts (:db config/config)))

(defdispatch handler list-resources)

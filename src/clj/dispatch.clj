(ns dispatch
  (:require [net.eighttrigrams.defn-over-http.core :refer [defdispatch-with-args]]
            [datastore.config :as config]
            [repository :refer [list-resources insert-issue]]))

(defdispatch-with-args handler 
  list-resources 
  insert-issue)

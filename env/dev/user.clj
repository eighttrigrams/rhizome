(ns user
  (:require [mount.core :as mount]
            server
            [datastore.config :as config]))

(def db (:db (config/ds)))

(defn start []
  (mount/start))

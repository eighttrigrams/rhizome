(ns user
  (:require [et.rz.server.main :as server]
            [et.rz.config :as config]))

(def db (:db (config/ds)))

(defn start []
  (server/start-http-server!))

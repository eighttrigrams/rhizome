(ns user
  (:require server
            [config :as config]))

(def db (:db (config/ds)))

(defn start []
  (server/start-http-server!))

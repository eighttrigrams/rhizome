(ns datastore.config
  (:require [mount.core :as mount]))

(defn- config-path [] (or (System/getenv "RHIZOME_CONFIG") "./config.edn"))

(defn ds [] (read-string (slurp (config-path))))

(mount/defstate config :start (ds) :stop nil)

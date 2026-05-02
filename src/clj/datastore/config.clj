(ns datastore.config
  (:require [mount.core :as mount]
            [datastore.connection :as connection]))

(defn- config-path [] (or (System/getenv "RHIZOME_CONFIG") "./config.edn"))

(defn ds []
  (let [c (read-string (slurp (config-path)))]
    (cond-> c
      (:db c) (update :db connection/make-datasource))))

(mount/defstate config :start (ds) :stop nil)

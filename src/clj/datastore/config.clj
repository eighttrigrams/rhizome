(ns datastore.config
  (:require [clojure.edn :as edn]
            [mount.core :as mount]
            [datastore.connection :as connection]))

(defn- config-path [] (or (System/getenv "RHIZOME_CONFIG") "./config.edn"))

(defn- coerce-numeric [s]
  (if (and (string? s) (re-matches #"-?\d+" s))
    (Long/parseLong s)
    s))

(defn- read-env [v]
  (let [[name default] (if (vector? v) v [v nil])]
    (coerce-numeric (or (System/getenv (str name)) default))))

(defn- read-or [vs]
  (some #(when (some? %) %) vs))

(def ^:private readers {'env read-env
                        'or  read-or})

(defn ds []
  (let [c (edn/read-string {:readers readers} (slurp (config-path)))]
    (cond-> c
      (:db c) (update :db connection/make-datasource))))

(mount/defstate config :start (ds) :stop nil)

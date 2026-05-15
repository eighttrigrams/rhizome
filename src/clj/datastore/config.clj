(ns datastore.config
  (:require [clojure.edn :as edn]
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

(def ^:private dev-homefolder "./files/")

(defn- apply-dev-homefolder [c]
  (if (:dev? c)
    (if (get-in c [:folders :homefolder])
      (throw (ex-info
              (str "config invalid: :folders :homefolder must not be set when :dev? is true "
                   "(dev mode hardcodes it to " dev-homefolder ")")
              {:config c}))
      (assoc-in c [:folders :homefolder] dev-homefolder))
    c))

(defn ds []
  (let [c (edn/read-string {:readers readers} (slurp (config-path)))
        c (apply-dev-homefolder c)]
    (cond-> c
      (:db c) (update :db connection/make-datasource))))

(def config (ds))

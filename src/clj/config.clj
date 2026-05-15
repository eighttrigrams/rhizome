(ns config
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
(def ^:private dev-dbname  "./rhizome.db")
(def ^:private test-dbname "./test/rhizome-test.db")
(def ^:private e2e-dbname  "./test/rhizome-e2e.db")

(defn- dev-dbname-for [c]
  (cond (:e2e? c)  e2e-dbname
        (:test? c) test-dbname
        :else      dev-dbname))

(defn- check-mode-flags [c]
  (when (and (:test? c) (:e2e? c))
    (throw (ex-info "config invalid: :test? and :e2e? are mutually exclusive"
                    {:config c})))
  (when (and (or (:test? c) (:e2e? c)) (not (:dev? c)))
    (throw (ex-info "config invalid: :test? / :e2e? require :dev? true"
                    {:config c})))
  c)

(defn- apply-dev-homefolder [c]
  (if (:dev? c)
    (if (get-in c [:folders :homefolder])
      (throw (ex-info
              (str "config invalid: :folders :homefolder must not be set when :dev? is true "
                   "(dev mode hardcodes it to " dev-homefolder ")")
              {:config c}))
      (assoc-in c [:folders :homefolder] dev-homefolder))
    c))

(defn- apply-dev-dbname [c]
  (if (:dev? c)
    (let [hardcoded (dev-dbname-for c)]
      (if (get-in c [:db :dbname])
        (throw (ex-info
                (str "config invalid: :db :dbname must not be set when :dev? is true "
                     "(hardcoded to " hardcoded ")")
                {:config c}))
        (assoc-in c [:db :dbname] hardcoded)))
    c))

(defn ds []
  (let [c (edn/read-string {:readers readers} (slurp (config-path)))
        c (check-mode-flags c)
        c (apply-dev-homefolder c)
        c (apply-dev-dbname c)]
    (cond-> c
      (:db c) (update :db connection/make-datasource))))

(def config (ds))

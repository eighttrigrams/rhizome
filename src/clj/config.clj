(ns config
  (:require [aero.core :as aero]
            [datastore.connection :as connection]))

(def ^:private config-path "./config.edn")

(def ^:private dev-homefolder "./files/")
(def ^:private dev-dbname  "./rhizome.db")
;; Unit / integration tests run against an in-memory SQLite — no file is
;; created and nothing leaks between runs. Users cannot opt into this via
;; config.edn; it's forced by the :test alias (see test-overrides). To
;; share one in-memory db across multiple JDBC connections we need
;; SQLite's shared-cache URI form rather than the bare ":memory:".
(def ^:private test-dbname "file::memory:?cache=shared")
(def ^:private e2e-dbname  "./test/rhizome-e2e.db")

(defn- e2e-mode? []
  (= "1" (System/getProperty "rhizome.e2e")))

(defn- test-mode? []
  (= "1" (System/getProperty "rhizome.test")))

(def ^:private e2e-overrides
  {:dev?      true
   :e2e?      true
   :bind-host "0.0.0.0"})

(def ^:private test-overrides
  {:dev?  true
   :test? true})

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
  (let [c (aero/read-config config-path)
        c (cond-> c
            (e2e-mode?)  (merge e2e-overrides)
            (test-mode?) (merge test-overrides))
        c (check-mode-flags c)
        c (apply-dev-homefolder c)
        c (apply-dev-dbname c)]
    (cond-> c
      (:db c) (update :db connection/make-datasource))))

(def config (ds))

(ns et.vp.ds.helpers
  (:require [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [tick.core :as t]
            [datastore.dialect :as dialect]))

(defn namespace-keys
  [ns-str m]
  (into {} (map (fn [[k v]] [(keyword (str ns-str "/" (name k))) v]) m)))

(defn un-namespace-keys [m] (into {} (map (fn [[k v]] [(keyword (name k)) v]) m)))

(defn gen-date [] (str "'" (t/format (t/formatter "YYYY-MM-dd HH:mm:ss") (t/date-time)) "'"))

(defn gen-iso-simple-date-str [] (t/format (t/formatter "YYYY-MM-dd") (t/date-time)))

(defn simplify-date
  [m]
  (update m :date
          (fn [v]
            (cond
              (nil? v)    nil
              (string? v) v
              :else       (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") v)))))

(defn instant-now [] (java.time.Instant/now))

(defn insert-and-get-id!
  "Run an INSERT and return the new rowid. Wraps the INSERT and the
   SELECT last_insert_rowid() in a transaction so they share a
   connection (SQLite's last_insert_rowid is per-connection)."
  [db sql-vec]
  (jdbc/with-transaction [tx db]
    (jdbc/execute-one! tx sql-vec)
    (-> (jdbc/execute-one! tx ["SELECT last_insert_rowid() AS id"]
                           {:builder-fn rs/as-unqualified-lower-maps})
        :id)))

(defn- parse-data
  [context]
  (if (:data context)
    (update context :data #(json/parse-string (dialect/parse-json-value %) true))
    context))

(defn int->bool
  "SQLite stores booleans as INTEGER 0/1; coerce back to Clojure
   boolean so callers can use plain truthiness without falling into
   the `(boolean 0) => true` trap."
  [v]
  (cond (nil? v)    nil
        (boolean? v) v
        (number? v) (not (zero? v))
        :else       (boolean v)))

(defn- coerce-bool-cols
  [m]
  (cond-> m
    (contains? m :is_context)            (update :is_context int->bool)
    (contains? m :hide_in_global_search) (update :hide_in_global_search int->bool)
    (contains? m :show_badge)            (update :show_badge int->bool)))

(defn post-process-base
  [query-result]
  (-> query-result
      un-namespace-keys
      simplify-date
      parse-data
      coerce-bool-cols
      (dissoc :searchable :embedding)))

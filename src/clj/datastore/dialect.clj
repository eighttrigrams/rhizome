(ns datastore.dialect
  (:require [datastore.config :refer [config]]
            [clojure.string :as str]))

(defn sqlite? ([] (sqlite? (:db config))) ([db-config] (= "sqlite" (:dbtype db-config))))

(defn now-sql [] (if (sqlite?) [:raw "datetime('now')"] [:raw "NOW()"]))

(defn array-agg-sql
  [col]
  (if (sqlite?)
    [[:raw (str "GROUP_CONCAT(" (name col) ")")] (keyword (str (name col) "_agg"))]
    [[:array_agg col] (keyword (str (name col) "_agg"))]))

(defn parse-array-result
  [value]
  (cond (nil? value) nil
        (string? value) (when (seq value)
                          (mapv #(try (Integer/parseInt %) (catch Exception _ %))
                            (str/split value #",")))
        :else (vec (.getArray value))))

(defn parse-json-value
  [value]
  (cond (nil? value) nil
        (string? value) value
        :else (.getValue value)))

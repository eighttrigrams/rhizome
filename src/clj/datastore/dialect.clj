(ns datastore.dialect
  "SQLite-specific helpers shared across the data layer.

   Used to be a Postgres/SQLite dispatcher; now just a thin SQLite-only
   utility module kept under its old name to avoid touching every caller."
  (:require [clojure.string :as str]))

(defn now-sql [] [:raw "datetime('now')"])

(defn array-agg-sql
  [col]
  [[:raw (str "GROUP_CONCAT(" (name col) ")")]
   (keyword (str (name col) "_agg"))])

(defn parse-array-result
  "GROUP_CONCAT comes back as a comma-joined string. Split and parse to
   ints where possible (the only producers in this codebase are id columns
   plus an annotation column; non-integer values pass through as strings)."
  [value]
  (cond
    (nil? value)    nil
    (string? value) (when (seq value)
                      (mapv #(try (Integer/parseInt %) (catch Exception _ %))
                            (str/split value #",")))
    :else           (vec value)))

(defn parse-json-value
  "SQLite stores JSON as TEXT, so values come back as plain strings."
  [value]
  value)

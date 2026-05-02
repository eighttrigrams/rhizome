(ns datastore.schema
  "Loads schema-sqlite.sql into a SQLite database.

   The naive split-on-semicolons doesn't work because trigger bodies
   contain semicolons inside BEGIN ... END. The splitter here tracks
   single-quote string literals and BEGIN/END nesting so trigger
   creation comes through as a single statement."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]))

(def ^:private schema-path "schema-sqlite.sql")

(defn- word-char? [^Character ch]
  (or (Character/isLetterOrDigit ch) (= ch \_)))

(defn- keyword-at?
  "Word-bounded case-insensitive keyword match at position `i`."
  [^String s ^String upper i kw]
  (let [n (count s)
        kn (count kw)]
    (and (>= n (+ i kn))
         (= kw (subs upper i (+ i kn)))
         (or (zero? i) (not (word-char? (.charAt s (dec i)))))
         (or (= n (+ i kn)) (not (word-char? (.charAt s (+ i kn))))))))

(defn split-statements
  "Split a SQLite script into individual statements. Honors single-quote
   strings and BEGIN ... END blocks (so CREATE TRIGGER survives intact)."
  [^String sql]
  (let [n (count sql)
        upper (str/upper-case sql)]
    (loop [i 0, depth 0, in-quote false, start 0, acc []]
      (if (>= i n)
        (let [tail (str/trim (subs sql start))]
          (if (str/blank? tail) acc (conj acc tail)))
        (let [c (.charAt sql i)]
          (cond
            in-quote
              (recur (inc i) depth (not (= c \')) start acc)

            (= c \')
              (recur (inc i) depth true start acc)

            (keyword-at? sql upper i "BEGIN")
              (recur (+ i 5) (inc depth) in-quote start acc)

            (keyword-at? sql upper i "END")
              (recur (+ i 3) (max 0 (dec depth)) in-quote start acc)

            (and (= c \;) (zero? depth))
              (let [stmt (str/trim (subs sql start i))]
                (recur (inc i) 0 false (inc i)
                       (if (str/blank? stmt) acc (conj acc stmt))))

            :else
              (recur (inc i) depth in-quote start acc)))))))

(defn apply-schema!
  "Apply schema-sqlite.sql to the given db spec. All CREATEs use
   IF NOT EXISTS, so this is idempotent."
  ([db] (apply-schema! db schema-path))
  ([db path]
   (doseq [stmt (split-statements (slurp path))]
     (jdbc/execute-one! db [stmt]))))

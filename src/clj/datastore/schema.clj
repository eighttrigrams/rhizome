(ns datastore.schema
  "Loads schema-sqlite.sql into a SQLite database.

   The naive split-on-semicolons doesn't work because trigger bodies
   contain semicolons inside BEGIN ... END. The splitter here tracks
   single-quote string literals, -- line comments and BEGIN/END nesting
   so trigger creation comes through as a single statement."
  (:require [clojure.string :as str]
            [datastore.connection :as connection]
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

(defn- line-comment-at?
  "Start of a -- comment at position `i`."
  [^String s i]
  (and (= \- (.charAt s i)) (< (inc i) (count s)) (= \- (.charAt s (inc i)))))

(defn- end-of-line
  "Index of the newline ending the line `i` is on, or the end of the string."
  [^String s i]
  (let [nl (.indexOf s "\n" ^int i)]
    (if (neg? nl) (count s) nl)))

(defn split-statements
  "Split a SQLite script into individual statements. Honors single-quote
   strings, -- line comments and BEGIN ... END blocks (so CREATE TRIGGER
   survives intact).

   Comments are skipped rather than scanned: an apostrophe in an English
   sentence would otherwise open a string literal that never closes, and
   every statement after it would be swallowed into one."
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

            (line-comment-at? sql i)
              (recur (end-of-line sql i) depth in-quote start acc)

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

(defn- vec-statement? [^String stmt]
  (boolean (re-find #"(?i)\busing\s+vec0\b" stmt)))

(defn- table-exists? [db table]
  (boolean (seq (jdbc/execute! db
                               ["SELECT name FROM sqlite_master WHERE type='table' AND name=?"
                                table]))))

(defn- column-exists? [db table column]
  (->> (jdbc/execute! db [(str "PRAGMA table_info(" table ")")])
       (some (fn [row]
               (= column (or (:table_info/name row) (:name row)))))))

(defn- ensure-column!
  "Add `column` to `table` as `decl` (e.g. \"TEXT\") if the table already
   exists and the column does not. Runs before the main CREATE statements
   so any indexes/triggers in the schema file that reference the new
   column see it on pre-existing dev DBs."
  [db table column decl]
  (when (and (table-exists? db table) (not (column-exists? db table column)))
    (jdbc/execute-one! db [(str "ALTER TABLE " table " ADD COLUMN " column " " decl)])))

(defn- vec-dim
  "FLOAT[N] dimension declared in a CREATE statement (or whole schema —
   items_vec is the only FLOAT[] user). nil when absent."
  [^String sql]
  (some->> sql (re-find #"(?i)FLOAT\[(\d+)\]") second Long/parseLong))

(defn- ensure-vec-dim!
  "Drop items_vec (and forget skips) when its declared dimension differs
   from the schema file's, so the CREATE that follows rebuilds it at the
   new dimension. Rows come back via the next embeddings backfill."
  [db schema-sql]
  (let [target (vec-dim schema-sql)
        current (some-> (jdbc/execute-one! db
                          ["SELECT sql FROM sqlite_master WHERE type='table' AND name='items_vec'"])
                        :sqlite_master/sql
                        vec-dim)]
    (when (and target current (not= target current))
      (jdbc/execute-one! db ["DROP TABLE items_vec"])
      (when (table-exists? db "items_vec_skipped")
        (jdbc/execute-one! db ["DELETE FROM items_vec_skipped"])))))

(defn apply-schema!
  "Apply schema-sqlite.sql to the given db spec. All CREATEs use
   IF NOT EXISTS, so this is idempotent. Statements that depend on the
   sqlite-vec extension are skipped when the extension is unavailable.
   Additive column migrations run first so the schema file can reference
   the new columns from indexes and triggers; an items_vec dimension
   change drops the table for the CREATE to rebuild."
  ([db] (apply-schema! db schema-path))
  ([db path]
   (let [sql (slurp path)]
     (ensure-column! db "items" "human_readable_id" "TEXT")
     (ensure-column! db "items" "description_source" "TEXT")
     (ensure-column! db "relations" "description" "TEXT")
     (ensure-column! db "relations" "is_part_of" "INTEGER NOT NULL DEFAULT 0")
     (ensure-column! db "relations" "part_of_sort_idx" "INTEGER NOT NULL DEFAULT -1")
     (ensure-column! db "relations" "description_source" "TEXT")
     (ensure-column! db "history" "source" "TEXT")
     (ensure-column! db "history" "title" "TEXT")
     (ensure-column! db "history" "tombstone" "INTEGER NOT NULL DEFAULT 0")
     (ensure-column! db "relation_history" "tombstone" "INTEGER NOT NULL DEFAULT 0")
     (ensure-column! db "youtube_poll_channels" "min_duration_minutes" "INTEGER")
     (when connection/vec-available?
       (ensure-vec-dim! db sql))
     (doseq [stmt (split-statements sql)
             :when (or connection/vec-available? (not (vec-statement? stmt)))]
       (jdbc/execute-one! db [stmt])))))

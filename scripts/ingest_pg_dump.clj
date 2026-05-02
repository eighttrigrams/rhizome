(ns ingest-pg-dump
  "Standalone ingest from a PostgreSQL plain-text pg_dump (`pg_dump -Fp`)
   into a fresh SQLite file. No live Postgres needed.

   Usage:
     clj -M:ingest-dump --src cometoid.2026-05-02.txt --dest ./rhizome.db [--force]

   Recognized tables: items, relations, history. Any other COPY blocks
   (e.g. legacy `events`) are skipped.

   Performance: triggers on items are dropped during the bulk insert;
   the FTS5 index is rebuilt in one shot afterwards."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datastore.schema :as schema]
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as p]
            [next.jdbc.result-set :as rs])
  (:import [java.io BufferedReader]))

(def ^:private known-tables #{"items" "relations" "history"})

(def ^:private bool-cols
  {"items"     #{"is_context" "hide_in_global_search"}
   "relations" #{"show_badge"}})

(def ^:private batch-size 2000)

(defn- decode-copy-token
  "Decode a single tab-separated COPY field. `\\N` is NULL; otherwise
   substitute the COPY-format escapes (\\t \\n \\r \\b \\f \\v \\\\)."
  [^String s]
  (cond
    (= s "\\N") nil
    (not (.contains s "\\")) s
    :else
    (let [n  (.length s)
          sb (StringBuilder. n)]
      (loop [i 0]
        (if (>= i n)
          (.toString sb)
          (let [c (.charAt s i)]
            (if (and (= c \\) (< (inc i) n))
              (let [d (.charAt s (inc i))]
                (case d
                  \b (.append sb \backspace)
                  \f (.append sb \formfeed)
                  \n (.append sb \newline)
                  \r (.append sb \return)
                  \t (.append sb \tab)
                  \v (.append sb (char 11))
                  \\ (.append sb \\)
                  (do (.append sb \\) (.append sb d)))
                (recur (+ i 2)))
              (do (.append sb c) (recur (inc i))))))))))

(defn- transform-cell [table col raw]
  (cond
    (nil? raw) nil
    (contains? (get bool-cols table) col)
      (case raw "t" 1 "f" 0 raw)
    :else raw))

(defn- run-batch! [db sql rows]
  (jdbc/with-transaction [tx db]
    (with-open [stmt (jdbc/prepare tx [sql])]
      (p/execute-batch! stmt rows))))

(defn- ingest-copy-block! [db ^BufferedReader r table cols]
  (let [col-list (str/join ", " cols)
        placeholders (str/join ", " (repeat (count cols) "?"))
        sql (str "INSERT INTO " table " (" col-list ") VALUES (" placeholders ")")]
    (loop [done 0
           batch (transient [])]
      (let [line (.readLine r)]
        (cond
          (nil? line)
            (throw (ex-info "EOF inside COPY block" {:table table}))

          (= line "\\.")
            (let [b (persistent! batch)
                  total (+ done (count b))]
              (when (seq b) (run-batch! db sql b))
              (println (format "  %s: %d rows" table total))
              total)

          :else
            (let [tokens (str/split line #"\t" -1)
                  row (mapv (fn [c v]
                              (transform-cell table c (decode-copy-token v)))
                            cols tokens)
                  batch' (conj! batch row)]
              (if (>= (count batch') batch-size)
                (do (run-batch! db sql (persistent! batch'))
                    (recur (+ done batch-size) (transient [])))
                (recur done batch'))))))))

(defn- skip-copy-block! [^BufferedReader r table]
  (loop [n 0]
    (let [line (.readLine r)]
      (cond
        (nil? line)     (throw (ex-info "EOF inside skipped COPY block" {:table table}))
        (= line "\\.")  (println (format "  skipped %s: %d rows" table n))
        :else           (recur (inc n))))))

(def ^:private copy-line-pattern
  #"COPY public\.(\w+) \(([^)]+)\) FROM stdin;")

(defn- bump-sequences! [db]
  (doseq [t ["items" "relations"]]
    (let [m (-> (jdbc/execute-one! db
                  [(str "SELECT COALESCE(MAX(id), 0) AS m FROM " t)]
                  {:builder-fn rs/as-unqualified-lower-maps})
                :m)]
      (jdbc/execute! db
        ["INSERT OR REPLACE INTO sqlite_sequence (name, seq) VALUES (?, ?)" t m]))))

(defn- drop-fts-triggers! [db]
  (doseq [t ["items_ai" "items_au" "items_ad"]]
    (jdbc/execute! db [(str "DROP TRIGGER IF EXISTS " t)])))

(defn- rebuild-fts! [db]
  (println "rebuilding items_fts...")
  ;; External-content FTS5 tables are rebuilt via the canonical 'rebuild'
  ;; command; that reads from the content table (items) and repopulates
  ;; the index in one shot.
  (jdbc/execute! db ["INSERT INTO items_fts(items_fts) VALUES('rebuild')"])
  ;; Re-create triggers (CREATE TRIGGER IF NOT EXISTS is idempotent; the
  ;; table CREATEs are no-ops).
  (schema/apply-schema! db))

(defn ingest!
  [{:keys [src dest force]}]
  (when-not src  (throw (ex-info "missing --src"  {})))
  (when-not dest (throw (ex-info "missing --dest" {})))
  (let [src-file  (io/file src)
        dest-file (io/file dest)]
    (when-not (.exists src-file)
      (throw (ex-info (str "no such source file: " src) {})))
    (when (and (.exists dest-file) (not force))
      (throw (ex-info (str "destination exists: " dest " (pass --force)") {})))
    (when (and (.exists dest-file) force)
      (println "removing existing" dest)
      (.delete dest-file))
    (require 'datastore.connection)
    (let [db ((resolve 'datastore.connection/make-datasource) {:dbname dest})]
      (println "creating SQLite schema in" dest)
      (jdbc/execute! db ["PRAGMA foreign_keys = OFF"])
      (jdbc/execute! db ["PRAGMA journal_mode = MEMORY"])
      (jdbc/execute! db ["PRAGMA synchronous = OFF"])
      (schema/apply-schema! db)
      (drop-fts-triggers! db)
      (println "ingesting from" src "...")
      (with-open [r (io/reader src-file)]
        (let [br ^BufferedReader r]
          (loop []
            (when-let [line (.readLine br)]
              (when-let [m (re-matches copy-line-pattern line)]
                (let [table (nth m 1)
                      cols  (->> (str/split (nth m 2) #",\s*")
                                 (mapv str/trim))]
                  (if (known-tables table)
                    (ingest-copy-block! db br table cols)
                    (skip-copy-block!  br table))))
              (recur)))))
      (rebuild-fts! db)
      (bump-sequences! db)
      (jdbc/execute! db ["PRAGMA foreign_keys = ON"])
      (let [counts (into {}
                         (for [t ["items" "relations" "history"]]
                           [(keyword t)
                            (-> (jdbc/execute-one! db
                                  [(str "SELECT COUNT(*) AS c FROM " t)]
                                  {:builder-fn rs/as-unqualified-lower-maps})
                                :c)]))]
        (println "final counts:" counts))
      (println "done."))))

(defn- parse-args [args]
  (loop [args args, m {}]
    (if (empty? args)
      m
      (let [a (first args)]
        (cond
          (= "--force" a) (recur (rest args) (assoc m :force true))
          (str/starts-with? a "--")
            (recur (drop 2 args) (assoc m (keyword (subs a 2)) (second args)))
          :else (recur (rest args) m))))))

(defn -main [& args]
  (try
    (ingest! (parse-args args))
    (catch Throwable t
      (binding [*out* *err*]
        (println "ERROR:" (.getMessage t))
        (when-let [d (ex-data t)] (println "  data:" (pr-str d))))
      (System/exit 1))))

(ns migrate-pg-to-sqlite
  "One-shot migration: copy items / relations / history from a Postgres
   Rhizome database into a fresh SQLite file.

   Usage:
     clj -M:migrate --src config.edn --dest ./rhizome.db [--force]

   --src   path to an EDN file with the Postgres connection under :db
           (the same shape as config.edn)
   --dest  path to the SQLite file to write to
   --force overwrite an existing dest file

   Idempotent for code reasons (CREATE TABLE IF NOT EXISTS), but the
   data copy assumes a clean dest. Use --force to delete an existing
   file before copying.

   Embeddings are migrated as JSON-encoded float arrays in items.embedding
   (TEXT). The FTS index and any sqlite-vec virtual table are *not*
   populated here — they're rebuilt by the app at startup."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datastore.schema :as schema]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [org.postgresql.util PGobject]))

(def ^:private batch-size 500)

(defn- iso-ts [^java.sql.Timestamp ts]
  (when ts (subs (.toString ts) 0 (min 19 (count (.toString ts))))))

(defn- iso-date [^java.sql.Date d]
  (when d (.toString d)))

(defn- bool->int [b] (if b 1 0))

(defn- pg-text
  "PG jsonb / vector / unknown types come back as PGobject; pull the text out."
  [v]
  (cond
    (nil? v) nil
    (instance? PGobject v) (.getValue ^PGobject v)
    :else (str v)))

(defn- has-column? [pg-db table col]
  (boolean
    (seq (jdbc/execute! pg-db
           ["SELECT 1 FROM information_schema.columns
              WHERE table_name = ? AND column_name = ?" table col]))))

(defn- has-table? [pg-db table]
  (boolean
    (seq (jdbc/execute! pg-db
           ["SELECT 1 FROM information_schema.tables
              WHERE table_name = ?" table]))))

(defn- run-schema! [sqlite-db]
  (schema/apply-schema! sqlite-db))

(defn- count-rows [db table]
  (-> (jdbc/execute-one! db [(str "SELECT COUNT(*) AS c FROM " table)]
                         {:builder-fn rs/as-unqualified-lower-maps})
      :c))

(defn- copy-items! [pg-db sqlite-db]
  (let [has-hide?  (has-column? pg-db "items" "hide_in_global_search")
        has-embed? (has-column? pg-db "items" "embedding")
        cols (cond-> ["id" "title" "short_title" "description"
                      "inserted_at" "updated_at" "updated_at_ctx"
                      "tags" "data" "is_context" "date" "sort_idx"
                      "annotation"]
               has-hide?  (conj "hide_in_global_search")
               has-embed? (conj "embedding"))
        select-sql (str "SELECT " (str/join ", " cols)
                        " FROM items ORDER BY id")
        insert-cols (str/join ", " cols)
        placeholders (str/join ", " (repeat (count cols) "?"))
        insert-sql (str "INSERT INTO items (" insert-cols
                        ") VALUES (" placeholders ")")
        rows (jdbc/execute! pg-db [select-sql]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (println (format "  items: %d rows" (count rows)))
    (doseq [batch (partition-all batch-size rows)]
      (jdbc/with-transaction [tx sqlite-db]
        (doseq [r batch]
          (let [vals (mapv (fn [c]
                             (let [v (get r (keyword c))]
                               (case c
                                 ("inserted_at" "updated_at" "updated_at_ctx") (iso-ts v)
                                 "date"                  (iso-date v)
                                 "is_context"            (bool->int v)
                                 "hide_in_global_search" (bool->int v)
                                 "data"                  (pg-text v)
                                 "embedding"             (pg-text v)
                                 v)))
                           cols)]
            (jdbc/execute! tx (into [insert-sql] vals))))))))

(defn- copy-relations! [pg-db sqlite-db]
  (let [rows (jdbc/execute! pg-db
               ["SELECT id, owner_id, target_id, show_badge, annotation
                  FROM relations ORDER BY id"]
               {:builder-fn rs/as-unqualified-lower-maps})]
    (println (format "  relations: %d rows" (count rows)))
    (doseq [batch (partition-all batch-size rows)]
      (jdbc/with-transaction [tx sqlite-db]
        (doseq [r batch]
          (jdbc/execute! tx
            ["INSERT INTO relations (id, owner_id, target_id, show_badge, annotation)
               VALUES (?, ?, ?, ?, ?)"
             (:id r) (:owner_id r) (:target_id r)
             (bool->int (:show_badge r)) (:annotation r)]))))))

(defn- copy-history! [pg-db sqlite-db]
  (when (has-table? pg-db "history")
    (let [rows (jdbc/execute! pg-db
                 ["SELECT id, text, version, created_at
                    FROM history ORDER BY id, version"]
                 {:builder-fn rs/as-unqualified-lower-maps})]
      (println (format "  history: %d rows" (count rows)))
      (doseq [batch (partition-all batch-size rows)]
        (jdbc/with-transaction [tx sqlite-db]
          (doseq [r batch]
            (jdbc/execute! tx
              ["INSERT INTO history (id, text, version, created_at)
                 VALUES (?, ?, ?, ?)"
               (:id r) (:text r) (:version r) (iso-ts (:created_at r))])))))))

(defn- bump-sequences! [sqlite-db]
  (doseq [t ["items" "relations"]]
    (let [m (-> (jdbc/execute-one! sqlite-db
                  [(str "SELECT COALESCE(MAX(id), 0) AS m FROM " t)]
                  {:builder-fn rs/as-unqualified-lower-maps})
                :m)]
      (jdbc/execute! sqlite-db
        ["INSERT OR REPLACE INTO sqlite_sequence (name, seq) VALUES (?, ?)"
         t m]))))

(defn- pg-spec [src-config]
  (let [db (:db src-config)]
    (when-not (= "postgresql" (:dbtype db))
      (throw (ex-info "src config :db is not postgresql" {:db db})))
    db))

(defn- sqlite-spec [path] {:dbtype "sqlite" :dbname path})

(defn run-migration!
  [{:keys [src dest force]}]
  (when-not src  (throw (ex-info "missing --src"  {})))
  (when-not dest (throw (ex-info "missing --dest" {})))
  (let [src-cfg     (edn/read-string (slurp src))
        sqlite-file (io/file dest)]
    (when (and (.exists sqlite-file) (not force))
      (throw (ex-info (str "destination already exists: " dest
                           " (pass --force to overwrite)") {})))
    (when (and (.exists sqlite-file) force)
      (println "removing existing" dest)
      (.delete sqlite-file))
    (let [pg-db     (pg-spec src-cfg)
          sqlite-db (sqlite-spec dest)]
      (println "creating SQLite schema in" dest)
      (jdbc/execute! sqlite-db ["PRAGMA foreign_keys = OFF"])
      (run-schema! sqlite-db)
      (println "copying data...")
      (copy-items!     pg-db sqlite-db)
      (copy-relations! pg-db sqlite-db)
      (copy-history!   pg-db sqlite-db)
      (bump-sequences! sqlite-db)
      (jdbc/execute! sqlite-db ["PRAGMA foreign_keys = ON"])
      (println "verifying counts...")
      (let [src-counts {:items     (count-rows pg-db     "items")
                        :relations (count-rows pg-db     "relations")
                        :history   (when (has-table? pg-db "history")
                                     (count-rows pg-db "history"))}
            dst-counts {:items     (count-rows sqlite-db "items")
                        :relations (count-rows sqlite-db "relations")
                        :history   (count-rows sqlite-db "history")}]
        (println "  postgres:" src-counts)
        (println "  sqlite:  " dst-counts)
        (when (or (not= (:items src-counts)     (:items dst-counts))
                  (not= (:relations src-counts) (:relations dst-counts))
                  (and (:history src-counts)
                       (not= (:history src-counts) (:history dst-counts))))
          (throw (ex-info "row count mismatch — migration aborted"
                          {:src src-counts :dst dst-counts}))))
      (println "done."))))

(defn- parse-args [args]
  (loop [args args, m {}]
    (if (empty? args)
      m
      (let [a (first args)]
        (cond
          (= "--force" a) (recur (rest args) (assoc m :force true))
          (str/starts-with? a "--")
            (recur (drop 2 args)
                   (assoc m (keyword (subs a 2)) (second args)))
          :else (recur (rest args) m))))))

(defn -main [& args]
  (try
    (run-migration! (parse-args args))
    (catch Throwable t
      (binding [*out* *err*]
        (println "ERROR:" (.getMessage t))
        (when-let [d (ex-data t)] (println "  data:" (pr-str d))))
      (System/exit 1))))

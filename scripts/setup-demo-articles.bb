#!/usr/bin/env bb

(require '[babashka.process :refer [shell]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def config (edn/read-string (slurp "./config.edn")))
(def db-config (:db config))
(def articles-path "./scripts/demo-articles.edn")

(when-not (= "sqlite" (:dbtype db-config))
  (binding [*out* *err*]
    (println "config.edn :db must be SQLite."))
  (System/exit 1))

(defn esc [s] (str/replace (str s) "'" "''"))

(defn sqlite-exec! [sql]
  (let [{:keys [exit out err]}
        (shell {:in sql :out :string :err :string :continue true}
               "sqlite3" (:dbname db-config))]
    (when-not (zero? exit)
      (binding [*out* *err*] (println "sqlite3 failed:" err))
      (System/exit 1))
    out))

(defn articles-context-id []
  (let [out (sqlite-exec! "SELECT id FROM items WHERE is_context=1 AND title='Articles' LIMIT 1;")
        s   (str/trim out)]
    (when (str/blank? s)
      (binding [*out* *err*]
        (println "No 'Articles' context found. Run scripts/setup-demo-contexts.bb first."))
      (System/exit 1))
    (Integer/parseInt s)))

(defn insert-article! [{:keys [title description]}]
  (let [sql (str "INSERT INTO items (title, short_title, description, data, is_context, "
                 "inserted_at, updated_at, updated_at_ctx) "
                 "VALUES ('" (esc title) "', '', '" (esc description) "', '{}', 0, "
                 "datetime('now'), datetime('now'), datetime('now'));"
                 "SELECT last_insert_rowid();")]
    (-> (sqlite-exec! sql) str/trim Integer/parseInt)))

(defn link! [owner-id target-id]
  (sqlite-exec!
   (format "INSERT INTO relations (owner_id, target_id, show_badge) VALUES (%d, %d, 1);"
           owner-id target-id)))

(defn -main []
  (let [articles (edn/read-string (slurp articles-path))
        ctx-id   (articles-context-id)]
    (println (str "Seeding " (count articles) " demo articles into 'Articles' (id " ctx-id ")"))
    (doseq [a articles]
      (let [item-id (insert-article! a)]
        (link! ctx-id item-id)
        (println " +" (:title a) "→ id" item-id)))
    (println "Done.")))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))

#!/usr/bin/env bb

(require '[babashka.process :refer [shell]]
         '[clojure.edn :as edn])

(def config (edn/read-string (slurp "./config.edn")))
(def db-config (:db config))

(when-not (= "sqlite" (:dbtype db-config))
  (binding [*out* *err*]
    (println "config.edn :db must be SQLite — rhizome no longer supports Postgres."))
  (System/exit 1))

(defn sqlite-exec! [sql & params]
  (let [full-sql (if (seq params)
                   (reduce (fn [s p] (clojure.string/replace-first s "?" (str "'" p "'"))) sql params)
                   sql)]
    (shell {:out :string :err :string} "sqlite3" (:dbname db-config) full-sql)))

(defn clear-database []
  (println "Clearing database...")
  (sqlite-exec! "DELETE FROM relations WHERE target_id > 0 OR owner_id > 0;")
  (sqlite-exec! "DELETE FROM items WHERE id > 0;"))

(defn create-context [title]
  (sqlite-exec! (str "INSERT INTO items (title, short_title, data, is_context, inserted_at, updated_at, updated_at_ctx) "
                     "VALUES (?, '', '{}', 1, datetime('now'), datetime('now'), datetime('now'));")
                title)
  (let [result (sqlite-exec! "SELECT last_insert_rowid();")
        id (-> (:out result) clojure.string/trim Integer/parseInt)]
    (println "Created context:" title "with id" id)
    id))

(def all-contexts
  ["Imports"
   "Files" "Documents" "Audio" "Video" "Image"
   "MP3s" "OGGs" "M4As" "WAVs" "MP4s" "FLVs" "MOVs"
   "PDFs" "TIFFs" "JPEGs" "PNGs" "WEBPs"
   "YouTube" "Substack" "GitHub" "Apple Podcasts" "Twitter"
   "YouTube Videos" "YouTube Channels" "Substacks" "Articles"
   "Podcast Episodes" "Podcasts" "GitHub Repo" "GitHub User"
   "Twitter Handles" "Poasts" "Library" "Video"
   "2020" "2021" "2022" "2023" "2024" "2025"])

(defn setup-contexts []
  (println "Setting up required contexts...")
  (doseq [context all-contexts]
    (create-context context))
  (println (str "Created " (count all-contexts) " contexts successfully!")))

(defn main [& args]
  (try
    (println (str "Using SQLite database: " (:dbname db-config)))
    (clear-database)
    (if (and (seq args) (= (first args) "0"))
      (println "Database cleared. No contexts created.")
      (do
        (setup-contexts)
        (println "Database setup complete!")))
    (catch Exception e
      (println "Error setting up database:")
      (println (.getMessage e))
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply main *command-line-args*))

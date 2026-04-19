(ns semsearch.backfill
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [cambium.core :as log]
            [semsearch.embedder :as embedder]))

(defn embed-and-store!
  "Embed one item (by id) using its description. Titles are intentionally
  excluded from the embedding input — rhizome titles tend to be truncated
  snippets of the body and would bias similarity. No-op when the item has
  no description."
  [db {:keys [id description]}]
  (when-not (str/blank? description)
    (let [v (embedder/embed-text description)
          vec-str (embedder/vec->pg-literal v)]
      (jdbc/execute-one! db
        ["UPDATE items SET embedding = ?::vector WHERE id = ?" vec-str id]))))

(defn backfill-missing!
  "Embed every item that currently has NULL embedding AND a non-empty
  description. Intended for REPL use. Returns the number of items embedded."
  [db]
  (let [rows (jdbc/execute! db
               [(str "SELECT id, title, description FROM items "
                     "WHERE embedding IS NULL "
                     "AND description IS NOT NULL "
                     "AND length(trim(description)) > 0")])]
    (log/info {:backfill {:candidates (count rows)}})
    (loop [rows rows done 0]
      (if-let [{id :items/id title :items/title description :items/description} (first rows)]
        (do (try (embed-and-store! db {:id id :title title :description description})
                 (catch Exception e
                   (log/error e (str "backfill failed for id " id))))
            (recur (rest rows) (inc done)))
        (do (log/info {:backfill {:completed done}}) done)))))

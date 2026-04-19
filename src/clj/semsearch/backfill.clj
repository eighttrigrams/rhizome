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
  description. Logs per-item progress (success as info, failure as warn —
  no stack traces). Returns {:embedded N :failed M}."
  [db]
  (let [rows (jdbc/execute! db
               [(str "SELECT id, description FROM items "
                     "WHERE embedding IS NULL "
                     "AND description IS NOT NULL "
                     "AND length(trim(description)) > 0")])
        total (count rows)]
    (log/info {:backfill {:candidates total}})
    (loop [rows rows ok 0 fail 0]
      (if-let [{id :items/id description :items/description} (first rows)]
        (let [n (inc (+ ok fail))
              success? (try (embed-and-store! db {:id id :description description})
                            (log/info (str "[" n "/" total "] embedded id " id))
                            true
                            (catch Exception e
                              (log/warn (str "[" n "/" total "] embed failed for id "
                                             id ": " (.getMessage e)))
                              false))]
          (if success?
            (recur (rest rows) (inc ok) fail)
            (recur (rest rows) ok (inc fail))))
        (do (log/info {:backfill {:embedded ok :failed fail}})
            {:embedded ok :failed fail})))))

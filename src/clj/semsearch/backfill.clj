(ns semsearch.backfill
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [cambium.core :as log]
            [semsearch.embedder :as embedder]))

(defn store-embedding!
  "Write an item's embedding to items_vec. vec0 doesn't accept INSERT OR
   REPLACE on its primary key, so we DELETE then INSERT in a transaction."
  [db id v]
  (jdbc/with-transaction [tx db]
    (jdbc/execute-one! tx ["DELETE FROM items_vec WHERE item_id = ?" id])
    (jdbc/execute-one! tx ["INSERT INTO items_vec(item_id, embedding) VALUES (?, ?)"
                           id (embedder/vec->json v)])))

(defn embed-and-store!
  "Embed one item's description and store it in items_vec. Titles are
   excluded from the embedding input. No-op when the item has no
   description."
  [db {:keys [id description]}]
  (when-not (str/blank? description)
    (when-let [v (embedder/embed-text description)]
      (store-embedding! db id v))))

(defn backfill-missing!
  "Embed every item that has a non-empty description but no row in
   items_vec. Logs per-item progress. Returns {:embedded N :failed M}."
  [db]
  (let [rows (jdbc/execute! db
               [(str "SELECT i.id, i.description FROM items i "
                     "LEFT JOIN items_vec v ON v.item_id = i.id "
                     "WHERE v.item_id IS NULL "
                     "AND i.description IS NOT NULL "
                     "AND length(trim(i.description)) > 0")])
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

(ns semsearch.backfill
  (:require [cambium.core :as log]))

;; Vector search is disabled during the SQLite migration. Both entry
;; points are kept as harmless stubs so the rest of the codebase (REST
;; mutations, REPL helpers) keeps compiling and running.
;; See MIGRATION_GUIDE.md > "Vector search".

(defn embed-and-store!
  "No-op. Pre-SQLite this embedded the item's description and stored a
   pgvector value; reintroduce when a SQLite vector backend is chosen."
  [_db _item]
  nil)

(defn backfill-missing!
  "No-op. Returns the same shape as before so REST callers don't break."
  [_db]
  (log/info "semsearch.backfill: vector search disabled — skipping")
  {:embedded 0 :failed 0 :skipped true})

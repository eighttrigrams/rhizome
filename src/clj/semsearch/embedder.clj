(ns semsearch.embedder)

;; Vector search is disabled in the SQLite migration. This namespace
;; remains so callers keep compiling; reintroduce real impls when the
;; vector backend (sqlite-vec, in-Clojure cosine, …) is chosen.
;; See MIGRATION_GUIDE.md > "Vector search".

(def embedding-dim 768)

(defn embed-text
  "No-op stub. Returns nil so callers treat the item as 'no embedding'."
  [_text]
  nil)

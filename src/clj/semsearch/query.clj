(ns semsearch.query
  (:require [cambium.core :as log]))

;; Vector search is disabled during the SQLite migration. The REST
;; endpoint that exposes this still routes through here, so we return
;; an empty result set rather than throwing.
;; See MIGRATION_GUIDE.md > "Vector search".

(defn search-related-items-vector
  "No-op stub. Returns no results."
  [_db _q _selected-id _opts]
  (log/info "semsearch.query: vector search disabled — returning []")
  [])

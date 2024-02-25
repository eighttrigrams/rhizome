(ns repository.insertion.substack-external
  (:require [repository.insertion.substack :as substack]))

(defn match? [title]
  (re-matches #"https://www.arktosjournal.com\/p\/.*" title))

(defn save-article [db url context-ids-set should-capture-summary?]
  ((substack/make:save-article true) db url context-ids-set should-capture-summary?))
  
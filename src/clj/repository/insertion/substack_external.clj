(ns repository.insertion.substack-external
  (:require [repository.insertion.substack :as substack]
            [datastore.config :as config]))

(defn match? [title]
  ;; or use (some identity values)
  (reduce #(or %1 %2) false
          (map (fn [url]
                 (re-matches (re-pattern 
                              (str "https://" url "\\/p\\/.*")) 
                             title))
               (-> config/config
                   :substack
                   :external-substacks))))

(defn save-article [db url context-ids-set]
  ((substack/make:save-article true) db url context-ids-set))
  
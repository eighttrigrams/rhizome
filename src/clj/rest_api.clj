(ns rest-api
  (:require [clojure.string :as str]
            [compojure.core :refer [context GET POST PUT]]
            [datastore.config :as config]
            [rest-api.middleware :as mw]
            [rest-api.mutations :as mutations]
            [rest-api.queries :as queries]))

(defn rest-routes
  []
  (mw/wrap-logging
   (context "/rest" []
           (GET "/describe" [] (queries/describe))
           (POST "/recording-mode/toggle" [] (mutations/toggle-recording-mode))
           (POST "/backfill/embeddings" [] (mutations/backfill-embeddings (:db config/config)))
           (GET "/contexts" [q by-exact limit]
                (cond by-exact (queries/find-contexts (:db config/config) q by-exact)
                      :else (queries/search-contexts (:db config/config) q limit)))
           (POST "/contexts" req (mutations/create-context (:db config/config) req))
           (GET "/items/by-sort-idx" req
                (let [qs (:query-string req)
                      params (into {} (map #(str/split % #"=" 2)
                                           (str/split (or qs "") #"&")))]
                  (queries/find-by-sort-idx (:db config/config)
                                            (get params "sort_idx")
                                            (get params "context_ids"))))
           (GET "/items" [q] (queries/search-items (:db config/config) q))
           (GET "/items/:id/related" [id q secondary_ids search_mode vector]
                (queries/get-related-items (:db config/config) id
                                           {:q q
                                            :secondary-ids secondary_ids
                                            :search-mode search_mode
                                            :vector? (= "true" vector)}))
           (GET "/items/:id/with-related" [id search_mode]
                (queries/get-item-with-related (:db config/config) id
                                               {:search-mode search_mode}))
           (GET "/items/:id" [id] (queries/get-item (:db config/config) id))
           (PUT "/items/:id" [id :as req]
                (mutations/update-item-description (:db config/config) id req))
           (POST "/items" req (mutations/create-item (:db config/config) req))
           (PUT "/relations" req (mutations/upsert-relation (:db config/config) req)))))

(ns rest-api
  (:require [clojure.string :as str]
            [compojure.core :refer [context GET POST PUT]]
            [config :as config]
            [rest-api.middleware :as mw]
            [rest-api.mutations :as mutations]
            [rest-api.queries :as queries]))

(defn rest-routes
  []
  (mw/wrap-logging
   (mw/wrap-refuse-writes
    (mw/wrap-require-reason
     (context "/api" []
           (GET "/describe" [] (queries/describe))
           (GET "/status" [] (queries/status))
           (POST "/recording-mode/toggle" [] (mutations/toggle-recording-mode))
           (POST "/backfill/embeddings" [] (mutations/backfill-embeddings (:db config/config)))
           (GET "/contexts" [q limit]
                (queries/search-contexts (:db config/config) q limit))
           (POST "/contexts" req (mutations/create-context (:db config/config) req))
           (GET "/items/by-sort-idx" req
                (let [qs (:query-string req)
                      params (into {} (map #(str/split % #"=" 2)
                                           (str/split (or qs "") #"&")))]
                  (queries/find-by-sort-idx (:db config/config)
                                            (get params "sort_idx")
                                            (get params "context_ids"))))
           (GET "/items" [q id]
                (cond id (queries/find-items (:db config/config) id)
                      :else (queries/search-items (:db config/config) q)))
           (GET "/items/:id/related" [id q secondary_ids search_mode vector part_of]
                (queries/get-related-items (:db config/config) id
                                           {:q q
                                            :secondary-ids secondary_ids
                                            :search-mode search_mode
                                            :vector? (= "true" vector)
                                            :part-of? (= "true" part_of)}))
           (GET "/items/:id/with-related" [id search_mode]
                (queries/get-item-with-related (:db config/config) id
                                               {:search-mode search_mode}))
           (GET "/items/:id" [id] (queries/get-item (:db config/config) id))
           (PUT "/items/:id" [id :as req]
                (mutations/update-item-description (:db config/config) id req))
           (POST "/items" req (mutations/create-item (:db config/config) req))
           (GET "/items/:id/related/deletion-preview" [id]
                (mutations/deletion-preview-related-items (:db config/config) id))
           (POST "/items/:id/related/delete" [id]
                 (mutations/delete-related-items (:db config/config) id))
           (PUT "/relations" req (mutations/upsert-relation (:db config/config) req)))))))

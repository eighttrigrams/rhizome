(ns rest-api
  (:require [clojure.string :as str]
            [compojure.core :refer [context GET POST PUT]]
            [config :as config]
            [rest-api.middleware :as mw]
            [rest-api.mutations :as mutations]
            [rest-api.queries :as queries]))

(defn rest-routes
  "The REST surface, over the db handle `db-fn` returns.

   The 0-arity looks the global handle up, which is what `server` has always
   done and still does. The 1-arity exists because the hub mounts these same
   routes over its own local DataSource (arch rework 2, step 2): one surface,
   two processes, no second copy of the route table.

   It is a *function* and not a handle because compojure builds these routes
   once and answers many requests from them, while the tests rebind
   `config/config` per test -- so a handle captured here would be the wrong one
   by the time it is used. Resolving at call time is the same lesson step 3 of
   the split learned in `db-harness/app-config`: a handle read at build time is
   a convention, one read at call time is a guard."
  ([] (rest-routes #(:db config/config)))
  ([db-fn]
  (mw/wrap-logging
   (mw/wrap-refuse-writes
    (mw/wrap-require-reason
     (context "/api" []
           (GET "/describe" [] (queries/describe))
           (GET "/status" [] (queries/status))
           (POST "/recording-mode/toggle" [] (mutations/toggle-recording-mode))
           (POST "/backfill/embeddings" [] (mutations/backfill-embeddings (db-fn)))
           (GET "/contexts" [q limit]
                (queries/search-contexts (db-fn) q limit))
           (POST "/contexts" req (mutations/create-context (db-fn) req))
           (GET "/items/by-sort-idx" req
                (let [qs (:query-string req)
                      params (into {} (map #(str/split % #"=" 2)
                                           (str/split (or qs "") #"&")))]
                  (queries/find-by-sort-idx (db-fn)
                                            (get params "sort_idx")
                                            (get params "context_ids"))))
           (GET "/items" [q id]
                (cond id (queries/find-items (db-fn) id)
                      :else (queries/search-items (db-fn) q)))
           (GET "/items/:id/related" [id q secondary_ids search_mode vector part_of level]
                (queries/get-related-items (db-fn) id
                                           {:q q
                                            :secondary-ids secondary_ids
                                            :search-mode search_mode
                                            :vector? (= "true" vector)
                                            :part-of? (= "true" part_of)
                                            ;; Raw, not parsed here: "meaningless
                                            ;; without part_of" and "not a number"
                                            ;; are both refusals, and the handler
                                            ;; is where refusals are worded.
                                            :level level}))
           (GET "/items/:id/with-related" [id search_mode]
                (queries/get-item-with-related (db-fn) id
                                               {:search-mode search_mode}))
           (GET "/items/:id/images" [id data kinds]
                (queries/item-images (db-fn)
                                     (:folders config/config)
                                     id
                                     {:data data :kinds kinds}))
           (GET "/items/:id" [id] (queries/get-item (db-fn) id))
           (PUT "/items/:id" [id :as req]
                (mutations/update-item-description (db-fn) id req))
           (POST "/items" req (mutations/create-item (db-fn) req))
           (GET "/items/:id/related/deletion-preview" [id]
                (mutations/deletion-preview-related-items (db-fn) id))
           (POST "/items/:id/related/delete" [id]
                 (mutations/delete-related-items (db-fn) id))
           (PUT "/relations" req (mutations/upsert-relation (db-fn) req))))))))

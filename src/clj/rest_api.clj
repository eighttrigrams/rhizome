(ns rest-api
  (:require [clojure.string :as str]
            [compojure.core :refer [context GET POST PUT]]
            [datastore.config :as config]
            [rest-api.handlers :as handlers]))

(defn rest-routes
  []
  (context "/rest" []
           (GET "/recording-mode" [] (handlers/get-recording-mode))
           (POST "/recording-mode/toggle" [] (handlers/toggle-recording-mode))
           (GET "/contexts" [q] (if q
                                  (handlers/search-contexts (:db config/config) q)
                                  (handlers/list-contexts (:db config/config))))
           (POST "/contexts" req (handlers/create-context (:db config/config) req))
           (GET "/items/by-sort-idx" req
                (let [qs (:query-string req)
                      params (into {} (map #(str/split % #"=" 2)
                                           (str/split (or qs "") #"&")))]
                  (handlers/find-by-sort-idx (:db config/config)
                                             (get params "sort_idx")
                                             (get params "context_ids"))))
           (GET "/items" [q] (handlers/search-items (:db config/config) q))
           (GET "/items/:id/related" [id q secondary_ids search_mode]
                (handlers/get-related-items (:db config/config) id
                                            {:q q
                                             :secondary-ids secondary_ids
                                             :search-mode search_mode}))
           (GET "/items/:id/with-related" [id search_mode]
                (handlers/get-item-with-related (:db config/config) id
                                                {:search-mode search_mode}))
           (GET "/items/:id" [id] (handlers/get-item (:db config/config) id))
           (PUT "/items/:id" [id :as req]
                (handlers/update-item-description (:db config/config) id req))
           (POST "/items" req (handlers/create-item (:db config/config) req))))

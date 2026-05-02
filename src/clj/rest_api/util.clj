(ns rest-api.util
  (:require [clojure.string :as str]
            [cheshire.core :as json]))

(defn json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status status :headers {"Content-Type" "application/json"} :body (json/generate-string body)}))

(defn parse-json-body
  [req]
  (try (some-> req
               :body
               slurp
               (json/parse-string true))
       (catch Exception _ nil)))


(defn item->api
  [{:keys [id title short_title description is_context data inserted_at updated_at date
           annotation hide_in_global_search]}]
  (cond-> {:id id
           :title title
           :short-title short_title
           :is-context (boolean is_context)
           :inserted-at inserted_at
           :updated-at updated_at}
    description (assoc :description description)
    date (assoc :date date)
    annotation (assoc :annotation annotation)
    (true? hide_in_global_search) (assoc :hide-in-global-search true)
    (-> data
        :contexts)
      (assoc :contexts
        (into {}
              (map (fn [[k v]] [(str k) (if (map? v) (:title v) v)])
                (-> data
                    :contexts))))))

(defn parse-int-opt
  [s]
  (when (and s (not (str/blank? s)))
    (try (Integer/parseInt (str/trim s)) (catch NumberFormatException _ nil))))

(defn parse-ids-csv
  [s]
  (when (and s (not (str/blank? s))) (into [] (keep parse-int-opt) (str/split s #","))))

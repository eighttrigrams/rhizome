(ns rest-api.util
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [et.vp.ds.helpers :as helpers]))

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


(defn- part-of-wholes
  "The wholes this item is a part of, as {whole-id sibling-index}. Read off the
   same contexts map `:contexts` is flattened from — the part-of standing of a
   relation is mirrored on the part, so an item already carries it.

   Kept as its own key rather than folded into `:contexts`, whose {id → title}
   shape callers already read."
  [data]
  (into {}
        (keep (fn [[k v]]
                (when (and (map? v) (:is-part-of? v))
                  [(str k) (or (:part-of-sort-idx v) -1)])))
        (:contexts data)))

(defn item->api
  [{:keys [id title short_title human_readable_id description is_context data inserted_at updated_at
           date annotation hide_in_global_search part_of_sort_idx part_of_path]
    :as item}]
  (cond-> {:id id
           :title title
           :short-title short_title
           :is-context (helpers/int->bool is_context)
           :inserted-at inserted_at
           :updated-at updated_at}
    human_readable_id (assoc :human-readable-id human_readable_id)
    description (assoc :description description)
    date (assoc :date date)
    annotation (assoc :annotation annotation)
    (helpers/int->bool hide_in_global_search) (assoc :hide-in-global-search true)
    ;; Only rows that came out of the parts query carry this: it is the item's
    ;; index under the one whole that was asked about, which is not a property
    ;; of the item and cannot be answered anywhere else.
    (contains? item :part_of_sort_idx) (assoc :part-of-sort-idx part_of_sort_idx)
    ;; The route this row was reached by, from the whole that was asked about
    ;; down to the row itself. Only the parts query carries it, and it is what
    ;; tells two rows for one node apart: at a level below the first, the same
    ;; item filed under two wholes comes back twice, and nothing else on the two
    ;; rows differs.
    (contains? item :part_of_path) (assoc :part-of-path part_of_path)
    (seq (part-of-wholes data)) (assoc :part-of (part-of-wholes data))
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

(ns rest-api.queries
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [clojure.string :as str]
            [cambium.core :as log]
            [et.vp.ds :as datastore]
            [et.vp.ds.search :as search]
            [et.vp.ds.helpers :refer [post-process-base]]
            [semsearch.query :as semsearch]
            [rest-api.util :refer [json-response item->api parse-int-opt parse-ids-csv]]))

(defn list-contexts
  "GET /rest/contexts — list the 200 most recently touched contexts (items with
  is_context=true). Returns {:id :title :short-title} tuples."
  [db]
  (let [rows (jdbc/execute! db
                            (sql/format {:select [:id :title :short_title :is_context :inserted_at
                                                  :updated_at]
                                         :from [:items]
                                         :where [:= :is_context true]
                                         :order-by [[:updated_at_ctx :desc]]
                                         :limit 200}))]
    (json-response (map (fn [row]
                          (let [r (post-process-base row)]
                            {:id (:id r) :title (:title r) :short-title (:short_title r)}))
                     rows))))

(defn search-contexts
  "GET /rest/contexts?q=<query> — search contexts by title/short-title using SQL
  LIKE. Returns up to 50 matches, most recently touched first."
  [db q]
  (let [pattern (str "%" q "%")
        rows (jdbc/execute! db
                            (sql/format {:select [:id :title :short_title]
                                         :from [:items]
                                         :where [:and [:= :is_context true]
                                                 [:or [:like :title [:inline pattern]]
                                                  [:like :short_title [:inline pattern]]]]
                                         :order-by [[:updated_at_ctx :desc]]
                                         :limit 50}))]
    (json-response (map (fn [row]
                          (let [r (post-process-base row)]
                            {:id (:id r) :title (:title r) :short-title (:short_title r)}))
                     rows))))

(defn get-item
  "GET /rest/items/:id — fetch a single item (context or leaf) by numeric id,
  including its description. 400 if id is not an integer. Note: currently
  returns 200 with an empty shell ({:id nil, :title nil, ...}) when no item
  exists for the id; callers should check `:id`."
  [db id]
  (try (let [item (datastore/get-item db {:id (Integer/parseInt id)})]
         (if item (json-response (item->api item)) (json-response 404 {:error "Item not found"})))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))))

(defn find-by-sort-idx
  "GET /rest/items/by-sort-idx?sort_idx=N&context_ids=a,b,c — find the item whose
  sort_idx equals N and which belongs to ALL listed contexts (intersection).
  Typical use: locate a page inside its book + chapter. 404 if no such item."
  [db sort-idx context-ids-str]
  (try (let [sort-idx (Integer/parseInt sort-idx)
             context-ids (mapv #(Integer/parseInt (str/trim %))
                           (str/split context-ids-str #","))
             base-query {:select [:items.id :items.title :items.sort_idx]
                         :from [:items]
                         :where [:and [:= :items.sort_idx [:inline sort-idx]]
                                 (into [:and]
                                       (map (fn [cid] [:in :items.id
                                                       {:select [:target_id]
                                                        :from [:relations]
                                                        :where [:= :owner_id [:inline cid]]}])
                                         context-ids))]
                         :limit 1}
             rows (jdbc/execute! db (sql/format base-query))]
         (if (seq rows)
           (let [r (first rows)]
             (json-response
               {:id (:items/id r) :title (:items/title r) :sort-idx (:items/sort_idx r)}))
           (json-response 404 {:error "Not found"})))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid parameters"}))))

(defn search-items
  "GET /rest/items?q=<query> — free-text search across all items (context and
  leaf). Returns up to 10 hits. Prefer context/intersection lookups via
  /items/:id/related when you can narrow by context."
  [db q]
  (try (let [items (search/search-items db (or q "") {:all-items? true} {:limit 10})]
         (json-response (map item->api items)))
       (catch Exception e
         (log/error e "REST API: search-items failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn get-related-items
  "GET /rest/items/:id/related?q=&secondary_ids=&search_mode=&vector= — list
  items related to the context :id. Optional free-text q; CSV secondary_ids
  enables intersection search (raises limit from 10 to 100). search_mode:
  0 = most recently touched first (default), 2 = ordered by sort_idx (limit
  5000), 5 = most recently added first.

  vector=true switches to semantic search: q is embedded via Ollama
  (nomic-embed-text) and items are ranked by cosine similarity. Requires a
  non-empty q. Only items with a non-empty description are embedded — both
  on ingestion (POST /rest/items, PUT /rest/items/:id) and by the REPL
  backfill — so title-only items never appear in vector results."
  [db id-str {:keys [q secondary-ids search-mode vector?]}]
  (try (let [selected-id (Integer/parseInt id-str)
             secondary (parse-ids-csv secondary-ids)]
         (if vector?
           (let [items (semsearch/search-related-items-vector
                         db q selected-id
                         {:secondary-context-ids secondary :limit 20})]
             (json-response (map item->api items)))
           (let [mode (parse-int-opt search-mode)
                 limit (cond (= 2 mode) 5000
                             (seq secondary) 100
                             :else 10)
                 items (search/search-related-items
                         db (or q "") selected-id
                         {:selected-secondary-contexts secondary :search-mode mode}
                         {:limit limit})]
             (json-response (map item->api items)))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))
       (catch IllegalArgumentException e (json-response 400 {:error (.getMessage e)}))
       (catch Exception e
         (log/error e "REST API: get-related-items failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn get-item-with-related
  "GET /rest/items/:id/with-related?search_mode= — for a non-context (leaf)
  item, returns {:item :related}. 400 if :id is a context, 404 if not found."
  [db id-str {:keys [search-mode]}]
  (try (let [id (Integer/parseInt id-str)
             mode (parse-int-opt search-mode)
             item (datastore/get-item db {:id id})]
         (cond (nil? item) (json-response 404 {:error "Item not found"})
               (:is_context item)
                 (json-response 400 {:error "with-related is only for non-context items"})
               :else (let [related (search/search-related-items db
                                                                ""
                                                                id
                                                                {:selected-secondary-contexts []
                                                                 :search-mode mode}
                                                                {})]
                       (json-response {:item (item->api item) :related (map item->api related)}))))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))
       (catch Exception e
         (log/error e "REST API: get-item-with-related failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn describe
  "GET /rest/describe — self-description of the REST API. Returns one entry per
  public handler in rest-api.queries and rest-api.mutations that carries a
  docstring: {:name :ns :arglists :doc}. Reads Clojure var metadata at runtime,
  so it stays accurate in AOT builds as long as :doc is not elided."
  []
  (json-response
    (->> ['rest-api.queries 'rest-api.mutations]
         (mapcat (fn [ns-sym] (when-let [n (find-ns ns-sym)] (ns-publics n))))
         (keep (fn [[sym v]]
                 (when-let [doc (:doc (meta v))]
                   (when-not (:no-describe (meta v))
                     {:name (str sym)
                      :ns (str (ns-name (.ns ^clojure.lang.Var v)))
                      :arglists (pr-str (:arglists (meta v)))
                      :doc doc}))))
         (sort-by (juxt :ns :name))
         vec)))

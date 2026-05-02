(ns rest-api.queries
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [clojure.string :as str]
            [cambium.core :as log]
            [et.vp.ds :as datastore]
            [et.vp.ds.search :as search]
            [semsearch.query :as semsearch]
            [rest-api.util :refer [json-response item->api parse-int-opt parse-ids-csv]]))

(defn search-contexts
  "GET /rest/contexts?q=<query>[&limit=N] — searches **global** contexts
  (that is, those which have is_context true and hide-in-global-search false)
  by title, short-title & tags, prefix search. Limit defaults to 10, most
  recently touched first."
  [db q limit]
  (try (let [n (or (parse-int-opt limit) 10)
             items (search/search-items db (or q "") {:exclude-hidden? true} {:limit n})]
         (json-response (map item->api items)))
       (catch Exception e
         (log/error e "REST API: search-contexts failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn find-contexts
  "GET /rest/contexts?by-exact=title&q=Foo&q=Bar%20Baz — exact-match lookup of
  global contexts (is_context true, hide-in-global-search false) by title.
  The q param may be repeated to match any of several titles. URL-encode each
  value; emojis and whitespace work when properly encoded.

  Returns 400 if q is missing or contains duplicates. Returns 404 if any
  requested title is missing from the result, or if any title matches more
  than one item — the response body lists :missing and :duplicates so callers
  can repair the input."
  [db q by-exact]
  (try (if-not (= by-exact "title")
         (json-response 400 {:error "only by-exact=title is supported"})
         (let [titles (cond (nil? q) [] (sequential? q) (vec q) :else [q])
               distinct-titles (vec (distinct titles))
               repeated-in-request (vec (distinct (for [[t xs] (group-by identity titles)
                                                        :when (> (count xs) 1)]
                                                    t)))]
           (cond (empty? titles)
                   (json-response 400 {:error "by-exact=title requires at least one q"})
                 (seq repeated-in-request)
                   (json-response 400
                                  {:error "duplicate titles in request"
                                   :repeated repeated-in-request})
                 :else
                   (let [items (search/find-items-by-exact-titles
                                 db distinct-titles {:exclude-hidden? true})
                         by-title (group-by :title items)
                         missing (vec (remove by-title distinct-titles))
                         duplicates (vec (keep (fn [[t xs]] (when (> (count xs) 1) t))
                                           by-title))]
                     (if (or (seq missing) (seq duplicates))
                       (json-response 404
                                      {:error "titles do not correspond 1-to-1 with items"
                                       :requested-count (count distinct-titles)
                                       :found-count (count items)
                                       :missing missing
                                       :duplicates duplicates})
                       (json-response (map item->api items)))))))
       (catch Exception e
         (log/error e "REST API: find-contexts failed")
         (json-response 500 {:error (.getMessage e)}))))

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

(def ^:private global-conventions
  ["Every mutation (POST/PUT/PATCH/DELETE) MUST include a non-blank \"reason\" field in its JSON body explaining why the change is being made. Requests without one are rejected with 400. The reason is recorded in server logs and is not repeated in individual endpoint docstrings."
   "Mutations are gated by recording mode: while OFF they are logged as intent and dropped (a stub response is returned). Toggle with POST /rest/recording-mode/toggle, or in-app with Option+Shift+W."])

(defn ^:no-describe describe
  "GET /rest/describe — self-description of the REST API. Returns
  {:conventions [...]  :endpoints [{:name :doc} ...]}. The :conventions
  list captures rules that apply to every mutation so individual endpoint
  docstrings don't have to repeat them. Endpoints come from public vars
  in rest-api.queries and rest-api.mutations that carry a docstring; vars
  marked ^:no-describe are excluded."
  []
  (json-response
    {:conventions global-conventions
     :endpoints (->> ['rest-api.queries 'rest-api.mutations]
                     (mapcat (fn [ns-sym] (when-let [n (find-ns ns-sym)] (ns-publics n))))
                     (keep (fn [[sym v]]
                             (when-let [doc (:doc (meta v))]
                               (when-not (:no-describe (meta v))
                                 {:name (str sym)
                                  :doc doc}))))
                     (sort-by :name)
                     vec)}))

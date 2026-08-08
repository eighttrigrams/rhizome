(ns rest-api.queries
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cambium.core :as log]
            [et.vp.ds :as datastore]
            [et.vp.ds.search :as search]
            [replica :as replica]
            [semsearch.query :as semsearch]
            [rest-api.util :refer [json-response item->api parse-int-opt parse-ids-csv]]))

(defn search-contexts
  "GET /api/contexts?q=<query>[&limit=N] — searches **global** contexts
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

(defn- numeric-id? [s] (boolean (re-matches #"\d+" s)))

(defn find-items
  "GET /api/items?id=Foo&id=Bar&id=123 — lookup items by id. The id parameter
  may be repeated. A value that is all digits is matched against the items
  primary key (parsed as an integer); a value with at least one non-digit
  character is matched against the human-readable-id column. The two id
  categories are dispatched to the data layer independently, so a column the
  caller didn't ask for is never scanned.

  Returns 400 if id is missing or any value is repeated. Returns 404 when an
  id has no match, or when an id matches more than one item — the response
  body lists :missing and :duplicates so callers can repair the input."
  [db id]
  (try (let [ids (cond (nil? id) [] (sequential? id) (vec id) :else [id])
             distinct-ids (vec (distinct ids))
             repeated (vec (distinct (for [[v xs] (group-by identity ids)
                                           :when (> (count xs) 1)]
                                       v)))]
         (cond (empty? ids)
                 (json-response 400 {:error "id is required (at least one value)"})
               (seq repeated)
                 (json-response 400 {:error "duplicate ids in request" :repeated repeated})
               :else
                 (let [{numeric true human-readable false}
                         (group-by numeric-id? distinct-ids)
                       numeric-ints (mapv #(Integer/parseInt %) (or numeric []))
                       items (search/find-items-by-ids
                               db {:numeric-ids numeric-ints
                                   :human-readable-ids (or human-readable [])})
                       by-numeric (group-by :id items)
                       by-human (group-by :human_readable_id items)
                       missing (vec (remove (fn [v]
                                              (if (numeric-id? v)
                                                (by-numeric (Integer/parseInt v))
                                                (by-human v)))
                                            distinct-ids))
                       duplicates (vec (distinct
                                         (concat (keep (fn [[k xs]]
                                                         (when (and k (> (count xs) 1))
                                                           (str k)))
                                                       by-numeric)
                                                 (keep (fn [[k xs]]
                                                         (when (and k (> (count xs) 1))
                                                           k))
                                                       by-human))))]
                   (if (or (seq missing) (seq duplicates))
                     (json-response 404
                                    {:error "ids do not correspond 1-to-1 with items"
                                     :requested-count (count distinct-ids)
                                     :found-count (count items)
                                     :missing missing
                                     :duplicates duplicates})
                     (json-response (map item->api items))))))
       (catch Exception e
         (log/error e "REST API: find-items failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn get-item
  "GET /api/items/:id — fetch a single item (context or leaf) by numeric id,
  including its description. 400 if id is not an integer. Note: currently
  returns 200 with an empty shell ({:id nil, :title nil, ...}) when no item
  exists for the id; callers should check `:id`."
  [db id]
  (try (let [item (datastore/get-item db {:id (Integer/parseInt id)})]
         (if item (json-response (item->api item)) (json-response 404 {:error "Item not found"})))
       (catch NumberFormatException _ (json-response 400 {:error "Invalid item ID"}))))

(defn find-by-sort-idx
  "GET /api/items/by-sort-idx?sort_idx=N&context_ids=a,b,c — find the item whose
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
  "GET /api/items?q=<query> — free-text search across all items (context and
  leaf). Returns up to 10 hits. Prefer context/intersection lookups via
  /items/:id/related when you can narrow by context."
  [db q]
  (try (let [items (search/search-items db (or q "") {:all-items? true} {:limit 10})]
         (json-response (map item->api items)))
       (catch Exception e
         (log/error e "REST API: search-items failed")
         (json-response 500 {:error (.getMessage e)}))))

(defn- level-refusal
  "What is wrong with the `level` parameter, in words, or nil when nothing is.

  A level past the ceiling is refused rather than clamped or answered empty. An
  empty list already means something here -- nothing is filed that deep -- and
  answering a level of 500 with one would say that about a question the database
  was never asked. The ceiling is a property of the query, not of the data, and
  the two must not come back looking alike."
  [level part-of?]
  (let [n (parse-int-opt level)]
    (cond (not part-of?) "level is only meaningful together with part_of=true"
          (or (nil? n) (< n 1)) "level must be a positive integer"
          (> n search/max-part-of-level)
            (str "level must be at most " search/max-part-of-level
                 ": one level costs one table in the join, and SQLite plans a join"
                 " over at most 64 of them"))))

(defn get-related-items
  "GET /api/items/:id/related?q=&secondary_ids=&search_mode=&vector=&part_of=&level=
  — list items related to the context :id. Optional free-text q; CSV secondary_ids
  enables intersection search (raises limit from 10 to 100). search_mode:
  0 = most recently touched first (default), 2 = ordered by sort_idx (limit
  5000), 5 = most recently added first.

  part_of=true narrows to the **parts** of :id — the relations marked as
  part-of edges — in sibling order: part_of_sort_idx ascending, the ones left
  unplaced (-1) after them, most recently touched first within each group.
  Limit 5000. Each item comes back with its \"part-of-sort-idx\" under this
  whole, so a caller can see which sibling index is free before writing one.
  Items merely related to :id are not listed. Ignores secondary_ids and
  search_mode, which have no meaning inside a hierarchy. It also wins over
  vector=true when both are given — the two ask different questions and this
  one is answered.

  level=N goes deeper into that tree. **Default 1**, the parts of :id. Level 2 is
  the parts of those, level N the nodes at depth exactly N — so the direct parts
  are NOT among the level-2 rows. Ordering is by the whole path down to a node
  and the unset -1 sorts last at every step of it; the conventions say it in
  full. 400 when level is given without part_of=true, when it is not a positive
  integer, and when it is **above 63** — one level costs one table in the join
  and SQLite plans a join over at most 64, so that is a real ceiling and it is
  said here rather than left to be met.

  Every part_of row carries **\"part-of-path\"**: the ids it was reached
  through, from :id down to the row itself, both ends included — so its length
  is level + 1 and its first element is always :id. This is what tells two rows
  for one node apart. The part-of edges are a DAG, so an item filed under two
  chapters of the same book appears twice at level 2, once per route, and the
  two rows are otherwise the same object; the path is the only thing on them
  that differs. Read it as the route, not as an identity: the same node under a
  second path is the same item, filed twice.

  vector=true switches to semantic search: q is embedded via Ollama
  (qwen3-embedding) and items are ranked by cosine similarity. Requires a
  non-empty q. Only items with a non-empty description are embedded — both
  on ingestion (POST /api/items, PUT /api/items/:id) and by the REPL
  backfill — so title-only items never appear in vector results."
  [db id-str {:keys [q secondary-ids search-mode vector? part-of? level]}]
  (try (let [selected-id (Integer/parseInt id-str)
             secondary (parse-ids-csv secondary-ids)
             ;; An empty level= is no level, the way an empty search_mode= is no
             ;; search mode: a caller's unset template variable, not a claim.
             level (when-not (str/blank? level) level)]
         (cond
           (and level (level-refusal level part-of?))
             (json-response 400 {:error (level-refusal level part-of?)})
           part-of?
             (let [items (search/search-related-items
                           db (or q "") selected-id
                           {:hierarchy-mode? true
                            ;; A level counts for the whole it was counted under,
                            ;; which over REST is always the one in the path.
                            :hierarchy-level {:context selected-id
                                              :level (or (parse-int-opt level) 1)}
                            :with-part-of-path? true}
                           {:limit 5000})]
               (json-response (map item->api items)))
           vector?
             (let [items (semsearch/search-related-items-vector
                           db q selected-id
                           {:selected-secondary-contexts secondary :limit 20})]
               (json-response (map item->api items)))
           :else
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
  "GET /api/items/:id/with-related?search_mode= — for a non-context (leaf)
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

(defn status
  "GET /api/status — this instance's role: {\"read-only-replica\": true|false}.
  True when it booted as a read-only replica (prod mode, no primary.nosync
  marker in its start directory): every mutating request is refused with 403 and
  its db is open read-only. False when writes are possible at all (recording
  mode still gates them). Decided once at startup, so it cannot change while the
  process runs."
  []
  (json-response {:read-only-replica (replica/read-only?)}))

(def ^:private global-conventions
  ["Every mutation (POST/PUT/PATCH/DELETE) MUST include a non-blank \"reason\" field in its JSON body explaining why the change is being made. Requests without one are rejected with 400. The reason is recorded in server logs and is not repeated in individual endpoint docstrings."
   "Mutations are gated by recording mode: while OFF they are logged as intent and dropped (a stub response is returned). Toggle with POST /api/recording-mode/toggle, or in-app with Option+Shift+W."
   "A read-only replica refuses writes: an instance that booted without its primary.nosync marker (prod mode) answers every mutating request -- recording-mode toggle and embeddings backfill included -- with 403 {\"read-only-replica\": true} and writes nothing; reads are unaffected. GET /api/status reports the role, and the role is fixed for the lifetime of the process."
   "A relation may additionally be a part-of edge (PUT /relations with \"is-part-of\": the target item is the whole, the source item one of its parts), ordered among its siblings by \"part-of-sort-idx\". That is a plain integer, ascending, and any integer is accepted. -1 is the one reserved value: it is the default, it means the part has no place yet, and it sorts AFTER every sibling that carries an index rather than ahead of 0. Every other negative is an ordinary index and does sort ahead of 0, so -2 puts a part in front of everything -- that is a way of saying \"first\" without renumbering the siblings, not a mistake. The index belongs to the edge and not to the item, so a part that sits under several wholes can take a different position under each; it is independent of every other sort index in the system."
   "The part-of edges form a directed acyclic graph, not a tree: a node may be part of several wholes, and nothing should assume a unique parent or a unique path to a root. A cycle is not allowed. Any write that would close one is refused with 409 and the response names the path that would close it, both in \"error\" and as ids in \"part-of-cycle\". Plain relations are not constrained this way."
   "The part-of layer is readable, and should be read before it is written to: part_of=true on GET /items/:id/related lists the parts of that whole in sibling order, each carrying its own \"part-of-sort-idx\", so a caller can see whether something is already filed and which index is free. From the other end, every item carries a \"part-of\" map of {whole-id: index} for the wholes it is a part of, alongside \"contexts\"."
   "Seen from one whole the part-of edges unroll into a tree, and that tree has levels. Level 1 is the parts of that whole -- what part_of=true lists -- level 2 the parts of those, and so on: level N is the nodes at depth exactly N, so the direct children are NOT among the level-2 nodes. A node's place at a level is decided by the whole path that reached it and not by its own sibling index alone: the tuple of part-of-sort-idx from that whole down to the node, compared component by component, with the reserved -1 sorting after every set index at EVERY component rather than only at the last. So everything under the first child comes before everything under the second, whatever indices are used further down. Reading level 1 in order, then each of those nodes' parts in order, reproduces exactly that ordering."
   "Because the part-of edges are a DAG and not a tree, a node can sit at a level by more than one route -- the same item filed under two different wholes that are themselves parts of the same whole. It belongs at each place it occupies: a level is as long as there are paths to it, not as there are distinct nodes in it, and collapsing the duplicates would throw away one of two positions somebody deliberately gave the same thing. In a DAG the number of paths can grow combinatorially with depth without any cycle being involved, so a reader walking the levels should expect a level to be longer than the graph is wide."])

(def ^:private skill-resource "rhizome-user/SKILL.md")

(defn- strip-frontmatter
  "Drop a leading YAML frontmatter block, so what /api/describe serves starts
  at the markdown itself."
  [md]
  (str/replace-first md #"(?s)\A---\n.*?\n---\n" ""))

(def ^:private skill-md
  (delay
    (when-let [r (io/resource skill-resource)]
      (str/trim (strip-frontmatter (slurp r))))))

(defn ^:no-describe describe
  "GET /api/describe — self-description of the REST API. Returns
  {:conventions [...]  :endpoints [{:name :doc} ...]  :skill \"...\"}. The
  :conventions list captures rules that apply to every mutation so individual
  endpoint docstrings don't have to repeat them. Endpoints come from public
  vars in rest-api.queries and rest-api.mutations that carry a docstring; vars
  marked ^:no-describe are excluded. :skill is the rhizome-user skill markdown
  (resources/rhizome-user/SKILL.md, frontmatter stripped), which teaches how
  to search and read rhizome well."
  []
  (json-response
    {:conventions global-conventions
     :skill @skill-md
     :endpoints (->> ['rest-api.queries 'rest-api.mutations]
                     (mapcat (fn [ns-sym] (when-let [n (find-ns ns-sym)] (ns-publics n))))
                     (keep (fn [[sym v]]
                             (when-let [doc (:doc (meta v))]
                               (when-not (:no-describe (meta v))
                                 {:name (str sym)
                                  :doc doc}))))
                     (sort-by :name)
                     vec)}))

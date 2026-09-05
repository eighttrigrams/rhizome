(ns rest-api.queries-test
  (:require [clojure.test :refer [deftest is testing]]
            [db-harness]
            [clojure.string :as str]
            [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [ring.mock.request :as mock]
            [ring.middleware.params :refer [wrap-params]]
            [config :as config]
            [rest-api :as rest-api]
            [rest-api.queries :as queries]
            [et.vp.ds.search :as search]
            [semsearch.embedder :as embedder]
            [semsearch.backfill :as backfill]
            [et.vp.ds :as ds]
            [et.vp.ds.relations :as relations]
            [provenance :as provenance]
            [et.vp.ds.search-test :refer [reset-db with-time db]]))

(def baseline-contexts
  ["Imports"
   "Files" "Documents" "Audio" "Video" "Image"
   "MP3s" "OGGs" "M4As" "WAVs" "MP4s" "FLVs" "MOVs"
   "PDFs" "TIFFs" "JPEGs" "PNGs" "WEBPs"
   "YouTube" "Substack" "GitHub" "Apple Podcasts" "Twitter"
   "YouTube Videos" "YouTube Channels" "Substacks" "Articles"
   "Podcast Episodes" "Podcasts" "GitHub Repo" "GitHub User"
   "Twitter Handles" "Poasts" "Library"
   "2020" "2021" "2022" "2023" "2024" "2025"])

(defn- seed-baseline-contexts! [db]
  (doseq [title baseline-contexts]
    (ds/new-context db {:title title})))

(defmacro test-with-fresh-db
  [description & body]
  `(testing ~description
     (reset-db)
     (seed-baseline-contexts! db)
     (with-time ~@body)))

(def ^:private handler (delay (wrap-params (rest-api/rest-routes))))

(defn- GET*
  [path]
  ;; The handlers run on `db-harness/app-config`, whose `:db` is the remote
  ;; handle: everything they do to the database leaves this process. What this
  ;; test does on its own account -- `reset-db`, the seeding above, every
  ;; assertion below -- stays on `db`, the DataSource. See `db-harness`.
  (with-redefs [config/config db-harness/app-config]
    (@handler (mock/request :get path))))

(defn- body-json [resp] (json/parse-string (:body resp) true))

(deftest describe-test
  (test-with-fresh-db "returns conventions + endpoint docs + the skill"
    (let [resp (GET* "/api/describe")
          body (body-json resp)
          endpoints (:endpoints body)
          names (set (map :name endpoints))
          conventions (:conventions body)
          skill (:skill body)]
      (is (= 200 (:status resp)))
      (is (string? skill) "the rhizome-user skill markdown is served as :skill")
      (is (re-find #"^# " skill)
          "the skill starts at the markdown — the YAML frontmatter is stripped")
      (is (not (str/includes? skill "name: rhizome-user")))
      (is (sequential? endpoints))
      (is (sequential? conventions))
      (is (some #(re-find #"reason" %) conventions)
          "the reason-required rule is documented in :conventions")
      (is (contains? names "create-item"))
      (is (contains? names "search-contexts"))
      (is (contains? names "find-items"))
      (is (not (contains? names "describe"))
          "describe itself is marked :no-describe and excluded")
      (is (every? (fn [h] (seq (:doc h))) endpoints))
      (is (every? (fn [h] (and (not (contains? h :ns)) (not (contains? h :arglists))))
                  endpoints)
          "ns and arglists are not exposed to API callers"))))

(deftest search-contexts-test
  (test-with-fresh-db "filters contexts by title substring (case-sensitive LIKE)"
    (ds/new-context db {:title "Books"})
    (ds/new-context db {:title "Blogposts"})
    (ds/new-context db {:title "People"})
    (let [resp (GET* "/api/contexts?q=Book")
          titles (set (map :title (body-json resp)))]
      (is (= 200 (:status resp)))
      (is (contains? titles "Books"))
      (is (not (contains? titles "Blogposts")))
      (is (not (contains? titles "People")))))

  (test-with-fresh-db "limit query param caps results"
    (ds/new-context db {:title "BookA"})
    (ds/new-context db {:title "BookB"})
    (ds/new-context db {:title "BookC"})
    (let [resp (GET* "/api/contexts?q=Book&limit=2")
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= 2 (count body)))))

  (test-with-fresh-db "hidden-in-global-search contexts are excluded"
    (ds/new-context db {:title "Zebra Visible"})
    (let [hidden (ds/new-context db {:title "Zebra Hidden"})]
      (jdbc/execute-one! db ["UPDATE items SET hide_in_global_search = true WHERE id = ?"
                             (:id hidden)]))
    (let [resp (GET* "/api/contexts?q=Zebra")
          titles (set (map :title (body-json resp)))]
      (is (= 200 (:status resp)))
      (is (contains? titles "Zebra Visible"))
      (is (not (contains? titles "Zebra Hidden"))))))

(defn- set-human-readable-id! [db item-id human-readable-id]
  (jdbc/execute-one! db
                     ["UPDATE items SET human_readable_id = ? WHERE id = ?"
                      human-readable-id item-id]))

(deftest find-items-test
  (test-with-fresh-db "looks up items by numeric primary id"
    (let [a (ds/new-context db {:title "Books"})
          b (ds/new-context db {:title "People"})
          resp (GET* (format "/api/items?id=%d&id=%d" (:id a) (:id b)))
          titles (set (map :title (body-json resp)))]
      (is (= 200 (:status resp)))
      (is (= #{"Books" "People"} titles))))

  (test-with-fresh-db "looks up items by human-readable id"
    (let [a (ds/new-context db {:title "Books"})]
      (set-human-readable-id! db (:id a) "books")
      (let [resp (GET* "/api/items?id=books")
            body (body-json resp)]
        (is (= 200 (:status resp)))
        (is (= ["Books"] (mapv :title body)))
        (is (= "books" (:human-readable-id (first body)))))))

  (test-with-fresh-db "mixes numeric and human-readable ids in one request"
    (let [a (ds/new-context db {:title "Books"})
          b (ds/new-context db {:title "People"})]
      (set-human-readable-id! db (:id a) "books")
      (let [resp (GET* (format "/api/items?id=books&id=%d" (:id b)))
            titles (set (map :title (body-json resp)))]
        (is (= 200 (:status resp)))
        (is (= #{"Books" "People"} titles)))))

  (test-with-fresh-db "an all-digits value is matched against the numeric id, not the human-readable column"
    (let [a (ds/new-context db {:title "Books"})]
      (set-human-readable-id! db (:id a) "12345")
      (let [resp (GET* "/api/items?id=12345")
            body (body-json resp)]
        (is (= 404 (:status resp)))
        (is (= ["12345"] (:missing body))))))

  (test-with-fresh-db "400 when id is missing"
    (let [resp (GET* "/api/items?")]
      ;; without id, the route falls through to search-items (q-based), so
      ;; this exercises the dispatcher: id present but empty.
      (is (= 200 (:status resp)))))

  (test-with-fresh-db "400 when caller repeats the same id"
    (let [a (ds/new-context db {:title "Books"})
          resp (GET* (format "/api/items?id=%d&id=%d" (:id a) (:id a)))
          body (body-json resp)]
      (is (= 400 (:status resp)))
      (is (= [(str (:id a))] (:repeated body)))))

  (test-with-fresh-db "404 when a requested id has no matching item"
    (let [a (ds/new-context db {:title "Books"})
          resp (GET* (format "/api/items?id=%d&id=missing-handle" (:id a)))
          body (body-json resp)]
      (is (= 404 (:status resp)))
      (is (= ["missing-handle"] (:missing body)))
      (is (= [] (:duplicates body))))))

(deftest get-item-test
  (test-with-fresh-db "returns a leaf item by id"
    (let [ctx (ds/new-context db {:title "Books"})
          item (ds/new-item db "The Prize" "prize" #{(:id ctx)} 1)
          resp (GET* (str "/api/items/" (:id item)))
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= "The Prize" (:title body)))
      (is (= false (:is-context body)))))

  (test-with-fresh-db "200 with an empty shell when the id does not exist"
    (let [resp (GET* "/api/items/999999")
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (nil? (:id body)))
      (is (nil? (:title body)))))

  (test-with-fresh-db "400 when the id is not an integer"
    (let [resp (GET* "/api/items/not-a-number")]
      (is (= 400 (:status resp))))))

(defn- reset-history!
  "`reset-db` empties items and relations but not `history`, and SQLite hands
  out the same ids again to the next test's items -- so a caution test that did
  not do this would be assessing some earlier test's versions along with its
  own."
  []
  (jdbc/execute-one! db ["delete from history"]))

(deftest get-item-caution-test
  (test-with-fresh-db "carries per-line caution for the current description"
    (reset-history!)
    (let [ctx (ds/new-context db {:title "Books"})
          item (ds/new-item db "The Prize" "prize" #{(:id ctx)} 1)
          id (:id item)
          ;; The owner writes two lines from the app; an agent then appends a
          ;; third through the API and leaves a trailing newline behind it.
          description "his one\nhis two\ntheir three\n"]
      (ds/update-context-description db {:id id :description "his one\nhis two"} "app")
      (ds/update-context-description db {:id id :description description} "api")
      (let [resp (GET* (str "/api/items/" id))
            body (body-json resp)
            {:keys [legend ranges]} (:caution body)]
        (is (= 200 (:status resp)))
        (is (= description (:description body)))
        (is (= [{:from 1 :to 2 :caution 1.0}
                {:from 3 :to 4 :caution 0.0}]
               ranges)
            "his two lines are sacred, the agent's line and the empty line it left are not")
        (is (= provenance/legend legend)
            "the legend rides along with the ranges rather than being looked up")
        (is (str/includes? (:body resp) "\"caution\"")
            "and all of it survives the trip through JSON")
        (testing "the ranges cover every line of the description as the client must split it"
          (is (= (count (str/split description #"\n" -1)) (:to (last ranges))))
          (is (= 1 (:from (first ranges))))
          (is (= (map :from (rest ranges)) (map (comp inc :to) (butlast ranges)))
              "and they run contiguously, with no line falling between two of them")))))

  (test-with-fresh-db "no caution key at all when the item has no description"
    (reset-history!)
    (let [ctx (ds/new-context db {:title "Books"})
          item (ds/new-item db "Title only" "title-only" #{(:id ctx)} 1)
          body (body-json (GET* (str "/api/items/" (:id item))))]
      (is (= "Title only" (:title body)))
      (is (not (contains? body :caution))
          "absent rather than empty: there is nothing to be careful in")))

  (test-with-fresh-db "the shape is documented where /api/describe reads from"
    ;; The docstring is the API doc -- there is no second catalogue to update.
    (let [doc (->> (:endpoints (body-json (GET* "/api/describe")))
                   (some (fn [{:keys [name doc]}] (when (= "get-item" name) doc))))]
      (is (some? doc))
      (is (str/includes? doc "caution"))
      (is (str/includes? doc "\"from\""))
      (is (str/includes? doc "\"to\""))
      (is (str/includes? doc "legend")))))

(deftest get-related-items-test
  (test-with-fresh-db "lists items in a context"
    (let [ctx (ds/new-context db {:title "Books"})]
      (ds/new-item db "The Prize" "prize" #{(:id ctx)} 1)
      (ds/new-item db "Sapiens" "sapiens" #{(:id ctx)} 2)
      (let [resp (GET* (str "/api/items/" (:id ctx) "/related"))
            titles (set (map :title (body-json resp)))]
        (is (= 200 (:status resp)))
        (is (= #{"The Prize" "Sapiens"} titles)))))

  (test-with-fresh-db "free-text q narrows the list"
    (let [ctx (ds/new-context db {:title "Books"})]
      (ds/new-item db "The Prize" "prize" #{(:id ctx)} 1)
      (ds/new-item db "Sapiens" "sapiens" #{(:id ctx)} 2)
      (let [resp (GET* (str "/api/items/" (:id ctx) "/related?q=Prize"))
            body (body-json resp)]
        (is (= 200 (:status resp)))
        (is (= ["The Prize"] (mapv :title body)))))))

(deftest find-by-sort-idx-test
  (test-with-fresh-db "finds an item by sort_idx inside a context"
    (let [ctx (ds/new-context db {:title "Books"})]
      (ds/new-item db "Page 5" "p5" #{(:id ctx)} 5)
      (let [resp (GET* (format "/api/items/by-sort-idx?sort_idx=5&context_ids=%d"
                               (:id ctx)))
            body (body-json resp)]
        (is (= 200 (:status resp)))
        (is (= "Page 5" (:title body)))
        (is (= 5 (:sort-idx body))))))

  (test-with-fresh-db "404 when no item matches"
    (let [ctx (ds/new-context db {:title "Books"})
          resp (GET* (format "/api/items/by-sort-idx?sort_idx=99&context_ids=%d"
                             (:id ctx)))]
      (is (= 404 (:status resp))))))

(deftest get-item-with-related-test
  (test-with-fresh-db "returns {:item :related} for a leaf item"
    (let [ctx (ds/new-context db {:title "Books"})
          item (ds/new-item db "The Prize" "prize" #{(:id ctx)} 1)
          resp (GET* (str "/api/items/" (:id item) "/with-related"))
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= "The Prize" (-> body :item :title)))
      (is (sequential? (:related body)))))

  (test-with-fresh-db "400 when the id refers to a context"
    (let [ctx (ds/new-context db {:title "Books"})
          resp (GET* (str "/api/items/" (:id ctx) "/with-related"))]
      (is (= 400 (:status resp))))))

(deftest search-items-test
  (test-with-fresh-db "free-text search finds items across all contexts"
    (let [ctx (ds/new-context db {:title "Books"})]
      (ds/new-item db "The Prize" "prize" #{(:id ctx)} 1)
      (let [resp (GET* "/api/items?q=Prize")
            body (body-json resp)]
        (is (= 200 (:status resp)))
        (is (some #(= "The Prize" (:title %)) body))))))

(defn- unit-vec
  "Returns an embedding-dim vector that is 1.0 at position `i` and 0
   elsewhere. Each i gives an axis orthogonal to the others."
  [i]
  (into [] (for [k (range embedder/embedding-dim)] (if (= k i) 1.0 0.0))))

(deftest ^:vector get-related-items-vector-test
    (test-with-fresh-db "ranks items by cosine distance to the embedded query"
      (let [texts-to-vecs {"The Prize"             (unit-vec 0)
                           "Sapiens"               (unit-vec 1)
                           "Cartesian Linguistics" (unit-vec 2)
                           (str embedder/query-prefix "history of oil") (unit-vec 0)}
            stub-embed (fn [text]
                         (or (get texts-to-vecs text)
                             (throw (ex-info "unexpected embed input" {:text text}))))]
        (with-redefs [embedder/embed-text stub-embed]
          (let [ctx (ds/new-context db {:title "Books"})
                a (ds/new-item db "The Prize" "p" #{(:id ctx)} 1)
                b (ds/new-item db "Sapiens" "s" #{(:id ctx)} 2)
                c (ds/new-item db "Cartesian Linguistics" "c" #{(:id ctx)} 3)]
            (backfill/store-embedding! db (:id a) (texts-to-vecs "The Prize"))
            (backfill/store-embedding! db (:id b) (texts-to-vecs "Sapiens"))
            (backfill/store-embedding! db (:id c) (texts-to-vecs "Cartesian Linguistics"))
            (let [resp (GET* (str "/api/items/" (:id ctx) "/related?vector=true&q=history%20of%20oil"))
                  body (body-json resp)]
              (is (= 200 (:status resp)))
              (is (= "The Prize" (-> body first :title))
                  "exact-match vector ranks first")
              (is (= #{"The Prize" "Sapiens" "Cartesian Linguistics"}
                     (set (map :title body)))
                  "all three embedded items come back"))))))

    (test-with-fresh-db "ignores items without an embedding"
      (with-redefs [embedder/embed-text (fn [_] (unit-vec 0))]
        (let [ctx (ds/new-context db {:title "Books"})
              a (ds/new-item db "Embedded" "e" #{(:id ctx)} 1)]
          (ds/new-item db "Not embedded" "n" #{(:id ctx)} 2)
          (backfill/store-embedding! db (:id a) (unit-vec 0))
          (let [resp (GET* (str "/api/items/" (:id ctx) "/related?vector=true&q=anything"))
                body (body-json resp)]
            (is (= 200 (:status resp)))
            (is (= ["Embedded"] (mapv :title body)))))))

    (test-with-fresh-db "400 when vector=true and q is empty"
      (with-redefs [embedder/embed-text (fn [_] (unit-vec 0))]
        (let [ctx (ds/new-context db {:title "Books"})
              resp (GET* (str "/api/items/" (:id ctx) "/related?vector=true&q="))]
          (is (= 400 (:status resp)))))))

(deftest part-of-read-side-test
  (test-with-fresh-db "the parts of a whole, in sibling order, each with its index"
    (let [whole (ds/new-context db {:title "Book"})
          one (ds/new-item db "One" "" #{(:id whole)} nil)
          two (ds/new-item db "Two" "" #{(:id whole)} nil)
          loose (ds/new-item db "Merely related" "" #{(:id whole)} nil)
          place! (fn [part idx]
                   (relations/set-the-containers-of-item!
                     db
                     (ds/get-item db {:id (:id part)})
                     {(:id whole) {:title "Book" :show-badge? true :is-context? true
                                   :is-part-of? true :part-of-sort-idx idx}}
                     false))]
      (place! two 2)
      (place! one 1)
      (let [parts (body-json (GET* (str "/api/items/" (:id whole) "/related?part_of=true")))]
        (is (= ["One" "Two"] (mapv :title parts))
            "the parts only, in sibling order")
        (is (= [1 2] (mapv :part-of-sort-idx parts))
            "each carrying its index under this whole, so a caller can see which is free"))
      (is (= ["Merely related" "One" "Two"]
             (sort (mapv :title (body-json (GET* (str "/api/items/" (:id whole) "/related"))))))
          "while the ordinary related list is unchanged")))
  (test-with-fresh-db "level= reads the levels below the first"
    (let [ctx! (fn [title] (ds/new-context db {:title title}))
          root (ctx! "Part-of demo")
          front (ds/new-item db "Front matter" "" #{(:id root)} nil)
          foundations (ctx! "I. Foundations")
          layer (ctx! "II. The part-of layer")
          hierarchy (ctx! "III. Hierarchy mode")
          appendix (ds/new-item db "Appendix, unfiled" "" #{(:id root)} nil)
          loose (ds/new-item db "Merely related, not a part" "" #{(:id root)} nil)
          table (ds/new-item db "The relations table" ""
                             #{(:id foundations) (:id layer)} nil)
          mirror (ds/new-item db "The contexts mirror" "" #{(:id foundations)} nil)
          check (ds/new-item db "The acyclicity check" "" #{(:id layer)} nil)
          sibling (ds/new-item db "The sibling index" "" #{(:id layer)} nil)
          strip (ds/new-item db "The strip" "" #{(:id hierarchy)} nil)
          becomes (ds/new-item db "What the item list becomes" "" #{(:id hierarchy)} nil)
          ;; One save per part naming every whole it belongs to: the save
          ;; rebuilds an item's relations out of the map it is handed, so a
          ;; second call naming only the other whole would drop the first edge.
          file! (fn [part wholes]
                  (relations/set-the-containers-of-item!
                    db
                    (ds/get-item db {:id (:id part)})
                    (into {}
                          (map (fn [[whole idx]]
                                 [(:id whole) {:title (:title whole)
                                               :show-badge? true
                                               :is-context? true
                                               :is-part-of? true
                                               :part-of-sort-idx idx}]))
                          wholes)
                    false))
          at (fn [level]
               (body-json (GET* (str "/api/items/" (:id root) "/related?part_of=true"
                                     (when level (str "&level=" level))))))]
      (file! front {root -2})
      (file! foundations {root 1})
      (file! layer {root 2})
      (file! hierarchy {root 3})
      (file! appendix {root -1})
      (file! mirror {foundations 2})
      (file! check {layer 1})
      (file! sibling {layer 2})
      (file! strip {hierarchy 1})
      (file! becomes {hierarchy 2})
      ;; The node with two routes into level 2, at a different index under each.
      (file! table {foundations 1 layer 5})
      (is (= ["Front matter" "I. Foundations" "II. The part-of layer" "III. Hierarchy mode"
              "Appendix, unfiled"]
             (mapv :title (at nil)))
          "no level is the first one, so every existing caller is unaffected")
      (is (= (at nil) (at 1)) "and level=1 is the same answer spelled out")
      (is (= [[(:id root) (:id front)] [(:id root) (:id foundations)] [(:id root) (:id layer)]
              [(:id root) (:id hierarchy)] [(:id root) (:id appendix)]]
             (mapv :part-of-path (at nil)))
          "each row saying the route it was reached by, :id first and itself last")
      (is (= ["The relations table" "The contexts mirror" "The acyclicity check"
              "The sibling index" "The relations table" "The strip"
              "What the item list becomes"]
             (mapv :title (at 2)))
          "level 2 in path order -- the same seven rows, in the same order, the strip shows")
      (is (= 7 (count (at 2))) "seven rows for six distinct items: once per path, not deduplicated")
      (is (= [[(:id root) (:id foundations) (:id table)]
              [(:id root) (:id layer) (:id table)]]
             (mapv :part-of-path
                   (filter #(= (:id table) (:id %)) (at 2))))
          "and the twice-listed node is told apart by its path, which is the only
           thing on the two rows that differs")
      (is (= [1 5] (mapv :part-of-sort-idx (filter #(= (:id table) (:id %)) (at 2))))
          "its index under each of the two wholes coming with it")
      (is (empty? (at 3)) "nothing is filed that deep")
      (is (not-any? #(= "Merely related, not a part" (:title %)) (at 2))
          "an item merely related to the root is at no level of it")))
  (test-with-fresh-db "level= is refused rather than guessed at"
    (let [root (ds/new-context db {:title "Book"})
          resp (fn [qs] (GET* (str "/api/items/" (:id root) "/related?" qs)))
          error (fn [qs] (:error (body-json (resp qs))))]
      (is (= 400 (:status (resp "level=2"))))
      (is (= "level is only meaningful together with part_of=true" (error "level=2"))
          "a level without part_of asks for a level of a list that has none")
      (is (= 400 (:status (resp "part_of=true&level=0"))))
      (is (= "level must be a positive integer" (error "part_of=true&level=0")))
      (is (= "level must be a positive integer" (error "part_of=true&level=deep")))
      (is (= 400 (:status (resp (str "part_of=true&level=" (inc search/max-part-of-level)))))
          "past the ceiling is a refusal, not an empty list -- an empty list here
           says nothing is filed that deep, which is a different fact")
      (is (str/includes? (error (str "part_of=true&level=" (inc search/max-part-of-level)))
                         (str search/max-part-of-level))
          "and it names the ceiling")
      (is (= 200 (:status (resp (str "part_of=true&level=" search/max-part-of-level))))
          "the ceiling itself is answerable")
      (is (= 200 (:status (resp "part_of=true&level="))) "an empty level= is no level")))
  (test-with-fresh-db "the documented ceiling is the enforced one"
    (is (str/includes? (:doc (meta #'queries/get-related-items)) (str search/max-part-of-level))
        "an agent should not have to discover the ceiling by hitting it, so the
         docstring quotes the number -- and has to go on quoting the right one"))
  (test-with-fresh-db "an item names the wholes it is a part of"
    (let [whole (ds/new-context db {:title "Book"})
          part (ds/new-item db "One" "" #{(:id whole)} nil)
          loose (ds/new-item db "Merely related" "" #{(:id whole)} nil)]
      (relations/set-the-containers-of-item!
        db
        (ds/get-item db {:id (:id part)})
        {(:id whole) {:title "Book" :show-badge? true :is-context? true
                      :is-part-of? true :part-of-sort-idx 4}}
        false)
      (is (= {(keyword (str (:id whole))) 4}
             (:part-of (body-json (GET* (str "/api/items/" (:id part))))))
          "so a caller can tell whether something is already filed")
      (is (nil? (:part-of (body-json (GET* (str "/api/items/" (:id loose))))))
          "an item that is part of nothing says nothing"))))

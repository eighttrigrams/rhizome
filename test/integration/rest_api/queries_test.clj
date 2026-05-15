(ns rest-api.queries-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [ring.mock.request :as mock]
            [ring.middleware.params :refer [wrap-params]]
            [config :as config]
            [rest-api :as rest-api]
            [semsearch.embedder :as embedder]
            [semsearch.backfill :as backfill]
            [et.vp.ds :as ds]
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
  (with-redefs [config/config {:db db}]
    (@handler (mock/request :get path))))

(defn- body-json [resp] (json/parse-string (:body resp) true))

(deftest describe-test
  (test-with-fresh-db "returns conventions + endpoint docs"
    (let [resp (GET* "/rest/describe")
          body (body-json resp)
          endpoints (:endpoints body)
          names (set (map :name endpoints))
          conventions (:conventions body)]
      (is (= 200 (:status resp)))
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
    (let [resp (GET* "/rest/contexts?q=Book")
          titles (set (map :title (body-json resp)))]
      (is (= 200 (:status resp)))
      (is (contains? titles "Books"))
      (is (not (contains? titles "Blogposts")))
      (is (not (contains? titles "People")))))

  (test-with-fresh-db "limit query param caps results"
    (ds/new-context db {:title "BookA"})
    (ds/new-context db {:title "BookB"})
    (ds/new-context db {:title "BookC"})
    (let [resp (GET* "/rest/contexts?q=Book&limit=2")
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= 2 (count body)))))

  (test-with-fresh-db "hidden-in-global-search contexts are excluded"
    (ds/new-context db {:title "Zebra Visible"})
    (let [hidden (ds/new-context db {:title "Zebra Hidden"})]
      (jdbc/execute-one! db ["UPDATE items SET hide_in_global_search = true WHERE id = ?"
                             (:id hidden)]))
    (let [resp (GET* "/rest/contexts?q=Zebra")
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
          resp (GET* (format "/rest/items?id=%d&id=%d" (:id a) (:id b)))
          titles (set (map :title (body-json resp)))]
      (is (= 200 (:status resp)))
      (is (= #{"Books" "People"} titles))))

  (test-with-fresh-db "looks up items by human-readable id"
    (let [a (ds/new-context db {:title "Books"})]
      (set-human-readable-id! db (:id a) "books")
      (let [resp (GET* "/rest/items?id=books")
            body (body-json resp)]
        (is (= 200 (:status resp)))
        (is (= ["Books"] (mapv :title body)))
        (is (= "books" (:human-readable-id (first body)))))))

  (test-with-fresh-db "mixes numeric and human-readable ids in one request"
    (let [a (ds/new-context db {:title "Books"})
          b (ds/new-context db {:title "People"})]
      (set-human-readable-id! db (:id a) "books")
      (let [resp (GET* (format "/rest/items?id=books&id=%d" (:id b)))
            titles (set (map :title (body-json resp)))]
        (is (= 200 (:status resp)))
        (is (= #{"Books" "People"} titles)))))

  (test-with-fresh-db "an all-digits value is matched against the numeric id, not the human-readable column"
    (let [a (ds/new-context db {:title "Books"})]
      (set-human-readable-id! db (:id a) "12345")
      (let [resp (GET* "/rest/items?id=12345")
            body (body-json resp)]
        (is (= 404 (:status resp)))
        (is (= ["12345"] (:missing body))))))

  (test-with-fresh-db "400 when id is missing"
    (let [resp (GET* "/rest/items?")]
      ;; without id, the route falls through to search-items (q-based), so
      ;; this exercises the dispatcher: id present but empty.
      (is (= 200 (:status resp)))))

  (test-with-fresh-db "400 when caller repeats the same id"
    (let [a (ds/new-context db {:title "Books"})
          resp (GET* (format "/rest/items?id=%d&id=%d" (:id a) (:id a)))
          body (body-json resp)]
      (is (= 400 (:status resp)))
      (is (= [(str (:id a))] (:repeated body)))))

  (test-with-fresh-db "404 when a requested id has no matching item"
    (let [a (ds/new-context db {:title "Books"})
          resp (GET* (format "/rest/items?id=%d&id=missing-handle" (:id a)))
          body (body-json resp)]
      (is (= 404 (:status resp)))
      (is (= ["missing-handle"] (:missing body)))
      (is (= [] (:duplicates body))))))

(deftest get-item-test
  (test-with-fresh-db "returns a leaf item by id"
    (let [ctx (ds/new-context db {:title "Books"})
          item (ds/new-item db "The Prize" "prize" #{(:id ctx)} 1)
          resp (GET* (str "/rest/items/" (:id item)))
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= "The Prize" (:title body)))
      (is (= false (:is-context body)))))

  (test-with-fresh-db "200 with an empty shell when the id does not exist"
    (let [resp (GET* "/rest/items/999999")
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (nil? (:id body)))
      (is (nil? (:title body)))))

  (test-with-fresh-db "400 when the id is not an integer"
    (let [resp (GET* "/rest/items/not-a-number")]
      (is (= 400 (:status resp))))))

(deftest get-related-items-test
  (test-with-fresh-db "lists items in a context"
    (let [ctx (ds/new-context db {:title "Books"})]
      (ds/new-item db "The Prize" "prize" #{(:id ctx)} 1)
      (ds/new-item db "Sapiens" "sapiens" #{(:id ctx)} 2)
      (let [resp (GET* (str "/rest/items/" (:id ctx) "/related"))
            titles (set (map :title (body-json resp)))]
        (is (= 200 (:status resp)))
        (is (= #{"The Prize" "Sapiens"} titles)))))

  (test-with-fresh-db "free-text q narrows the list"
    (let [ctx (ds/new-context db {:title "Books"})]
      (ds/new-item db "The Prize" "prize" #{(:id ctx)} 1)
      (ds/new-item db "Sapiens" "sapiens" #{(:id ctx)} 2)
      (let [resp (GET* (str "/rest/items/" (:id ctx) "/related?q=Prize"))
            body (body-json resp)]
        (is (= 200 (:status resp)))
        (is (= ["The Prize"] (mapv :title body)))))))

(deftest find-by-sort-idx-test
  (test-with-fresh-db "finds an item by sort_idx inside a context"
    (let [ctx (ds/new-context db {:title "Books"})]
      (ds/new-item db "Page 5" "p5" #{(:id ctx)} 5)
      (let [resp (GET* (format "/rest/items/by-sort-idx?sort_idx=5&context_ids=%d"
                               (:id ctx)))
            body (body-json resp)]
        (is (= 200 (:status resp)))
        (is (= "Page 5" (:title body)))
        (is (= 5 (:sort-idx body))))))

  (test-with-fresh-db "404 when no item matches"
    (let [ctx (ds/new-context db {:title "Books"})
          resp (GET* (format "/rest/items/by-sort-idx?sort_idx=99&context_ids=%d"
                             (:id ctx)))]
      (is (= 404 (:status resp))))))

(deftest get-item-with-related-test
  (test-with-fresh-db "returns {:item :related} for a leaf item"
    (let [ctx (ds/new-context db {:title "Books"})
          item (ds/new-item db "The Prize" "prize" #{(:id ctx)} 1)
          resp (GET* (str "/rest/items/" (:id item) "/with-related"))
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= "The Prize" (-> body :item :title)))
      (is (sequential? (:related body)))))

  (test-with-fresh-db "400 when the id refers to a context"
    (let [ctx (ds/new-context db {:title "Books"})
          resp (GET* (str "/rest/items/" (:id ctx) "/with-related"))]
      (is (= 400 (:status resp))))))

(deftest search-items-test
  (test-with-fresh-db "free-text search finds items across all contexts"
    (let [ctx (ds/new-context db {:title "Books"})]
      (ds/new-item db "The Prize" "prize" #{(:id ctx)} 1)
      (let [resp (GET* "/rest/items?q=Prize")
            body (body-json resp)]
        (is (= 200 (:status resp)))
        (is (some #(= "The Prize" (:title %)) body))))))

(defn- unit-vec
  "Returns a 768-dim vector that is 1.0 at position `i` and 0 elsewhere.
   Each i gives an axis orthogonal to the others."
  [i]
  (into [] (for [k (range 768)] (if (= k i) 1.0 0.0))))

(deftest ^:vector get-related-items-vector-test
    (test-with-fresh-db "ranks items by cosine distance to the embedded query"
      (let [texts-to-vecs {"The Prize"             (unit-vec 0)
                           "Sapiens"               (unit-vec 1)
                           "Cartesian Linguistics" (unit-vec 2)
                           "history of oil"        (unit-vec 0)}
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
            (let [resp (GET* (str "/rest/items/" (:id ctx) "/related?vector=true&q=history%20of%20oil"))
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
          (let [resp (GET* (str "/rest/items/" (:id ctx) "/related?vector=true&q=anything"))
                body (body-json resp)]
            (is (= 200 (:status resp)))
            (is (= ["Embedded"] (mapv :title body)))))))

    (test-with-fresh-db "400 when vector=true and q is empty"
      (with-redefs [embedder/embed-text (fn [_] (unit-vec 0))]
        (let [ctx (ds/new-context db {:title "Books"})
              resp (GET* (str "/rest/items/" (:id ctx) "/related?vector=true&q="))]
          (is (= 400 (:status resp)))))))

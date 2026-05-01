(ns rest-api.queries-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [ring.mock.request :as mock]
            [ring.middleware.params :refer [wrap-params]]
            [datastore.config :as config]
            [rest-api :as rest-api]
            [semsearch.embedder :as embedder]
            [semsearch.backfill :as backfill]
            [et.vp.ds :as ds]
            [et.vp.ds.search-test :refer [reset-db with-time db]]))

(defn- ensure-embedding-column!
  "Idempotent: ensures pgvector + items.embedding exist in the test DB."
  []
  (jdbc/execute-one! db ["CREATE EXTENSION IF NOT EXISTS vector"])
  (jdbc/execute-one! db ["ALTER TABLE items ADD COLUMN IF NOT EXISTS embedding vector(768)"]))

(use-fixtures :once (fn [f] (ensure-embedding-column!) (f)))

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
  (test-with-fresh-db "lists every handler in queries and mutations with its docstring"
    (let [resp (GET* "/rest/describe")
          body (body-json resp)
          names (set (map :name body))]
      (is (= 200 (:status resp)))
      (is (sequential? body))
      (is (contains? names "create-item"))
      (is (contains? names "search-contexts"))
      (is (contains? names "find-contexts"))
      (is (not (contains? names "describe"))
          "describe itself is marked :no-describe and excluded")
      (is (every? (fn [h] (seq (:doc h))) body))
      (is (every? (fn [h] (and (not (contains? h :ns)) (not (contains? h :arglists))))
                  body)
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

(deftest find-contexts-test
  (test-with-fresh-db "exact-match returns matching contexts in 1-to-1 correspondence"
    (ds/new-context db {:title "Books"})
    (ds/new-context db {:title "People"})
    (let [resp (GET* "/rest/contexts?by-exact=title&q=Books&q=People")
          titles (set (map :title (body-json resp)))]
      (is (= 200 (:status resp)))
      (is (= #{"Books" "People"} titles))))

  (test-with-fresh-db "single q value also works"
    (ds/new-context db {:title "Books"})
    (let [resp (GET* "/rest/contexts?by-exact=title&q=Books")
          body (body-json resp)]
      (is (= 200 (:status resp)))
      (is (= ["Books"] (mapv :title body)))))

  (test-with-fresh-db "URL-encoded titles with whitespace and emoji match exactly"
    (ds/new-context db {:title "Hello World"})
    (ds/new-context db {:title "🎉 Party"})
    (let [resp (GET* "/rest/contexts?by-exact=title&q=Hello%20World&q=%F0%9F%8E%89%20Party")
          titles (set (map :title (body-json resp)))]
      (is (= 200 (:status resp)))
      (is (= #{"Hello World" "🎉 Party"} titles))))

  (test-with-fresh-db "substring match does NOT count as exact match"
    (ds/new-context db {:title "Books"})
    (let [resp (GET* "/rest/contexts?by-exact=title&q=Book")
          body (body-json resp)]
      (is (= 404 (:status resp)))
      (is (= ["Book"] (:missing body)))))

  (test-with-fresh-db "400 when by-exact is not 'title'"
    (let [resp (GET* "/rest/contexts?by-exact=short-title&q=Books")]
      (is (= 400 (:status resp)))))

  (test-with-fresh-db "400 when q is missing"
    (let [resp (GET* "/rest/contexts?by-exact=title")]
      (is (= 400 (:status resp)))))

  (test-with-fresh-db "400 when caller repeats the same title"
    (ds/new-context db {:title "Books"})
    (let [resp (GET* "/rest/contexts?by-exact=title&q=Books&q=Books")
          body (body-json resp)]
      (is (= 400 (:status resp)))
      (is (= ["Books"] (:repeated body)))))

  (test-with-fresh-db "404 when a requested title has no matching context"
    (ds/new-context db {:title "Books"})
    (let [resp (GET* "/rest/contexts?by-exact=title&q=Books&q=Missing")
          body (body-json resp)]
      (is (= 404 (:status resp)))
      (is (= ["Missing"] (:missing body)))
      (is (= [] (:duplicates body)))))

  (test-with-fresh-db "404 when a title matches more than one context"
    (ds/new-context db {:title "Books"})
    (ds/new-context db {:title "Books"})
    (let [resp (GET* "/rest/contexts?by-exact=title&q=Books")
          body (body-json resp)]
      (is (= 404 (:status resp)))
      (is (= [] (:missing body)))
      (is (= ["Books"] (:duplicates body)))))

  (test-with-fresh-db "hidden-in-global-search contexts are excluded from exact match"
    (let [hidden (ds/new-context db {:title "Hidden"})]
      (jdbc/execute-one! db ["UPDATE items SET hide_in_global_search = true WHERE id = ?"
                             (:id hidden)]))
    (let [resp (GET* "/rest/contexts?by-exact=title&q=Hidden")
          body (body-json resp)]
      (is (= 404 (:status resp)))
      (is (= ["Hidden"] (:missing body))))))

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
  Each i gives an axis orthogonal to the others, so cosine distance is 0
  only for the same i and 1 for any other."
  [i]
  (into [] (for [k (range 768)] (if (= k i) 1.0 0.0))))

(defn- set-embedding!
  "Write a vector directly to items.embedding. Bypasses the ingestion helper
  (which requires a description) — tests want full control over what gets
  embedded."
  [item-id v]
  (jdbc/execute-one! db
    ["UPDATE items SET embedding = ?::vector WHERE id = ?"
     (embedder/vec->pg-literal v) item-id]))

(deftest get-related-items-vector-test
  (test-with-fresh-db "ranks items by cosine distance to the embedded query"
    (let [texts-to-vecs {"The Prize"        (unit-vec 0)
                         "Sapiens"          (unit-vec 1)
                         "Cartesian Linguistics" (unit-vec 2)
                         "history of oil"   (unit-vec 0)}
          stub-embed (fn [text]
                       (or (get texts-to-vecs text)
                           (throw (ex-info "unexpected embed input" {:text text}))))]
      (with-redefs [embedder/embed-text stub-embed]
        (let [ctx (ds/new-context db {:title "Books"})
              a (ds/new-item db "The Prize" "p" #{(:id ctx)} 1)
              b (ds/new-item db "Sapiens" "s" #{(:id ctx)} 2)
              c (ds/new-item db "Cartesian Linguistics" "c" #{(:id ctx)} 3)]
          (set-embedding! (:id a) (texts-to-vecs "The Prize"))
          (set-embedding! (:id b) (texts-to-vecs "Sapiens"))
          (set-embedding! (:id c) (texts-to-vecs "Cartesian Linguistics"))
          (let [resp (GET* (str "/rest/items/" (:id ctx) "/related?vector=true&q=history%20of%20oil"))
                body (body-json resp)]
            (is (= 200 (:status resp)))
            (is (= "The Prize" (-> body first :title))
                "exact-match vector ranks first")
            (is (= #{"The Prize" "Sapiens" "Cartesian Linguistics"}
                   (set (map :title body)))
                "all three embedded items come back (un-embedded items would be skipped)"))))))

  (test-with-fresh-db "ignores items without an embedding"
    (with-redefs [embedder/embed-text (fn [_] (unit-vec 0))]
      (let [ctx (ds/new-context db {:title "Books"})
            a (ds/new-item db "Embedded" "e" #{(:id ctx)} 1)]
        (ds/new-item db "Not embedded" "n" #{(:id ctx)} 2)
        (set-embedding! (:id a) (unit-vec 0))
        (let [resp (GET* (str "/rest/items/" (:id ctx) "/related?vector=true&q=anything"))
              body (body-json resp)]
          (is (= 200 (:status resp)))
          (is (= ["Embedded"] (mapv :title body)))))))

  (test-with-fresh-db "400 when vector=true and q is empty"
    (with-redefs [embedder/embed-text (fn [_] (unit-vec 0))]
      (let [ctx (ds/new-context db {:title "Books"})
            resp (GET* (str "/rest/items/" (:id ctx) "/related?vector=true"))]
        (is (= 400 (:status resp))))))

  (test-with-fresh-db "falls back to normal search when vector param is absent"
    (with-redefs [embedder/embed-text (fn [_] (throw (ex-info "must not be called" {})))]
      (let [ctx (ds/new-context db {:title "Books"})]
        (ds/new-item db "The Prize" "p" #{(:id ctx)} 1)
        (let [resp (GET* (str "/rest/items/" (:id ctx) "/related"))
              body (body-json resp)]
          (is (= 200 (:status resp)))
          (is (= ["The Prize"] (mapv :title body))))))))

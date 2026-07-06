(ns semsearch.threshold-query-test
  "Integration tests for the blue-mode similarity-threshold query
   (semsearch.query/search-related-items-vector-threshold), exercised through
   core -> ds.search -> real sqlite-vec. Tagged ^:vector so `make test`
   excludes them when the sqlite-vec dylib is absent.

   Embeddings are crafted 2D unit vectors so cosine similarities are exact and
   readable: similarity to the query vector (1,0) equals the vector's x
   component. A=1.0, B=0.8, C=0.6."
  (:require [clojure.test :refer [deftest is testing]]
            [semsearch.embedder :as embedder]
            [semsearch.query :as query]
            [semsearch.backfill :as backfill]
            [et.vp.ds :as ds]
            [et.vp.ds.search :as search]
            [et.vp.ds.search-test :refer [reset-db with-time db]]))

(defn- vec-2d
  "embedding-dim unit vector with `x` on axis 0 and `y` on axis 1, rest 0.
   Cosine similarity to (vec-2d 1.0 0.0) equals x when x^2 + y^2 = 1."
  [x y]
  (into [] (for [k (range embedder/embedding-dim)]
             (case k 0 (double x) 1 (double y) 0.0))))

(def ^:private q-vec (vec-2d 1.0 0.0))

(defmacro with-fresh-db [& body]
  `(do (reset-db) (with-time ~@body)))

(defn- titles [items] (mapv :title items))

(defn- approx= [a b] (< (Math/abs (- (double a) (double b))) 1.0e-6))

;; Seed one context with related items A(1.0) B(0.8) C(0.6) (+ optional
;; non-embedded item), return the context.
(defn- seed-abc! []
  (let [ctx (ds/new-context db {:title "Books"})
        a (ds/new-item db "A" "a" #{(:id ctx)} 1)
        b (ds/new-item db "B" "b" #{(:id ctx)} 2)
        c (ds/new-item db "C" "c" #{(:id ctx)} 3)]
    (backfill/store-embedding! db (:id a) (vec-2d 1.0 0.0))
    (backfill/store-embedding! db (:id b) (vec-2d 0.8 0.6))
    (backfill/store-embedding! db (:id c) (vec-2d 0.6 0.8))
    ctx))

(defn- threshold-query [ctx threshold]
  (query/search-related-items-vector-threshold
    db "history of oil" (:id ctx) {:threshold threshold :limit 100}))

(deftest ^:vector threshold-filters-in-the-backend
  (with-fresh-db
    (with-redefs [embedder/embed-text (fn [_] q-vec)]
      (testing "lowering the threshold strictly widens the result set (SQL WHERE)"
        (let [ctx (seed-abc!)
              r90 (threshold-query ctx 0.9)
              r70 (threshold-query ctx 0.7)
              r50 (threshold-query ctx 0.5)
              r00 (threshold-query ctx 0.0)]
          (is (= #{"A"} (set (titles (:items r90)))) "only sim>=0.9")
          (is (= #{"A" "B"} (set (titles (:items r70)))) "sim>=0.7")
          (is (= #{"A" "B" "C"} (set (titles (:items r50)))) "sim>=0.5")
          (is (= #{"A" "B" "C"} (set (titles (:items r00)))) "sim>=0.0")
          (is (< (count (:items r90)) (count (:items r70)) (count (:items r50)))
              "count grows monotonically as the threshold drops"))))))

(deftest ^:vector keeps-original-order-no-rerank
  (with-fresh-db
    (with-redefs [embedder/embed-text (fn [_] q-vec)]
      (testing "blue keeps the base ordering; only green re-ranks by similarity"
        (let [ctx (seed-abc!)
              base (titles (search/search-related-items db "" (:id ctx) {} {}))
              green (titles (query/search-related-items-vector db "history of oil" (:id ctx) {:limit 100}))
              blue (titles (:items (threshold-query ctx 0.0)))]
          (is (= ["A" "B" "C"] green) "green ranks closest-first by similarity")
          (is (= base blue) "blue returns items in the unmodified base order")
          (is (not= green blue) "blue is NOT re-ranked by similarity"))))))

(deftest ^:vector snap-returns-top-ties
  (with-fresh-db
    (with-redefs [embedder/embed-text (fn [_] q-vec)]
      (testing "threshold nil snaps to the max similarity, incl. exact ties"
        (let [ctx (ds/new-context db {:title "Books"})
              a1 (ds/new-item db "A1" "a1" #{(:id ctx)} 1)
              a2 (ds/new-item db "A2" "a2" #{(:id ctx)} 2)
              b  (ds/new-item db "B" "b" #{(:id ctx)} 3)]
          (backfill/store-embedding! db (:id a1) (vec-2d 1.0 0.0))
          (backfill/store-embedding! db (:id a2) (vec-2d 1.0 0.0))
          (backfill/store-embedding! db (:id b) (vec-2d 0.8 0.6))
          (let [r (threshold-query ctx nil)]
            (is (= #{"A1" "A2"} (set (titles (:items r))))
                "both items at the exact max similarity come back, B does not")
            (is (approx= 1.0 (:vector-threshold r)) "threshold snapped to max")
            (is (approx= 1.0 (:vector-max-similarity r)))))))))

(deftest ^:vector reports-similarity-bounds-and-annotations
  (with-fresh-db
    (with-redefs [embedder/embed-text (fn [_] q-vec)]
      (testing "returns similarity bounds for the slider and annotates each item"
        (let [ctx (seed-abc!)
              r (threshold-query ctx 0.0)
              by-title (into {} (map (juxt :title identity) (:items r)))]
          (is (approx= 1.0 (:vector-max-similarity r)))
          (is (approx= 0.6 (:vector-min-similarity r)))
          (is (approx= 1.0 (:similarity (by-title "A"))))
          (is (approx= 0.8 (:similarity (by-title "B"))))
          (is (approx= 0.6 (:similarity (by-title "C")))))))))

(deftest ^:vector at-max-threshold-keeps-top-item
  (with-fresh-db
    (with-redefs [embedder/embed-text (fn [_] q-vec)]
      (testing "an explicit threshold equal to the max does not drop the top item (float-safe)"
        (let [ctx (seed-abc!)
              max-sim (:vector-max-similarity (threshold-query ctx nil))
              r (threshold-query ctx max-sim)]
          (is (= #{"A"} (set (titles (:items r))))))))))

(deftest ^:vector at-min-threshold-keeps-lowest-item
  (with-fresh-db
    (with-redefs [embedder/embed-text (fn [_] q-vec)]
      (testing "threshold at (or a hair above) min-sim still returns the lowest item"
        ;; The range slider serializes its value with less precision than a
        ;; double, so 'full-left' arrives slightly ABOVE the true min. Without
        ;; the bottom float-guard the lowest-similarity item (C) drops out.
        (let [ctx (seed-abc!)
              min-sim (:vector-min-similarity (threshold-query ctx nil))
              at-min (threshold-query ctx min-sim)
              hair-above (threshold-query ctx (+ min-sim 1.0e-12))]
          (is (= #{"A" "B" "C"} (set (titles (:items at-min))))
              "threshold == min-sim returns all incl. the lowest (C)")
          (is (= #{"A" "B" "C"} (set (titles (:items hair-above))))
              "threshold a hair above min still returns the lowest (float-safe bottom guard)"))))))

(deftest ^:vector excludes-non-embedded-items
  (with-fresh-db
    (with-redefs [embedder/embed-text (fn [_] q-vec)]
      (testing "items without an embedding never appear, even at threshold 0"
        (let [ctx (seed-abc!)]
          (ds/new-item db "NoEmbedding" "ne" #{(:id ctx)} 4)
          (let [r (threshold-query ctx 0.0)]
            (is (= #{"A" "B" "C"} (set (titles (:items r))))
                "the non-embedded item is filtered out by the INNER JOIN")))))))

(deftest ^:vector blank-query-short-circuits
  (with-fresh-db
    (with-redefs [embedder/embed-text (fn [_] (throw (ex-info "should not embed blank q" {})))]
      (testing "blank q returns an empty result without embedding or throwing"
        (let [ctx (seed-abc!)
              r (query/search-related-items-vector-threshold db "" (:id ctx) {:threshold 0.5 :limit 100})]
          (is (= [] (:items r)))
          (is (nil? (:vector-max-similarity r)))
          (is (nil? (:vector-min-similarity r))))))))

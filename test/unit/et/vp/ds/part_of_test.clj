(ns et.vp.ds.part-of-test
  "A part-of relation is written down twice -- as a row in `relations` and as an
   entry in the `contexts` map inside `items.data` -- and the save paths rebuild
   one out of the other. These tests pin the two to each other."
  (:require [clojure.test :refer [deftest is testing]]
            [et.vp.ds :as ds]
            [et.vp.ds.relations :as relations]
            [et.vp.ds.search :as search]
            [et.vp.ds.search-test :refer [test-with-reset-db-and-time db]]
            [next.jdbc :as jdbc]))

(defn- row
  "The part-of columns of the relation that makes whole-id the whole of part-id."
  [whole-id part-id]
  (let [r (jdbc/execute-one! db
                             ["SELECT is_part_of, part_of_sort_idx FROM relations
                               WHERE owner_id = ? AND target_id = ?"
                              whole-id part-id])]
    {:is-part-of? (= 1 (:relations/is_part_of r))
     :part-of-sort-idx (:relations/part_of_sort_idx r)}))

(defn- mirror
  "The same relation as the `contexts` entry in the part's items.data."
  [whole-id part-id]
  (-> (ds/get-item db {:id part-id})
      (get-in [:data :contexts whole-id])
      (select-keys [:is-part-of? :part-of-sort-idx])))

(defn- book-with-chapter
  "A context and an item under it, the item marked as part of the context at
   sort index `idx` -- the save the edit modal makes."
  [idx]
  (let [book (ds/new-context db {:title "Book"})
        chapter (ds/new-item db "Chapter" "" #{(:id book)} nil)]
    (relations/set-the-containers-of-item! db
                                           chapter
                                           {(:id book) {:title "Book"
                                                        :show-badge? true
                                                        :is-context? true
                                                        :is-part-of? true
                                                        :part-of-sort-idx idx}}
                                           false)
    [book chapter]))

(deftest a-saved-part-of-relation-is-written-to-both-representations
  (test-with-reset-db-and-time "the relations row and the items.data mirror agree"
    (let [[book chapter] (book-with-chapter 3)]
      (is (= {:is-part-of? true :part-of-sort-idx 3} (row (:id book) (:id chapter))))
      (is (= {:is-part-of? true :part-of-sort-idx 3} (mirror (:id book) (:id chapter))))))
  (test-with-reset-db-and-time "and clearing the flag clears both"
    (let [[book chapter] (book-with-chapter 3)]
      (relations/set-the-containers-of-item! db
                                             (ds/get-item db {:id (:id chapter)})
                                             {(:id book) {:title "Book"
                                                          :show-badge? true
                                                          :is-context? true
                                                          :is-part-of? false
                                                          :part-of-sort-idx -1}}
                                             false)
      (is (= {:is-part-of? false :part-of-sort-idx -1} (row (:id book) (:id chapter))))
      (is (= {:is-part-of? false :part-of-sort-idx -1} (mirror (:id book) (:id chapter)))))))

(deftest a-save-that-fails-halfway-leaves-the-relations-as-they-were
  (test-with-reset-db-and-time
    "the rows and the mirror are one transaction -- a failure writing the mirror
     must not leave the rows already rewritten, since the next save rebuilds the
     rows out of the mirror"
    (let [[book chapter] (book-with-chapter 3)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (with-redefs [relations/update-collection-title-in-collection-items
                                   (fn [& _] (throw (ex-info "mirror write failed" {})))]
                     (relations/set-the-containers-of-item!
                       db
                       (ds/get-item db {:id (:id chapter)})
                       {(:id book) {:title "Book" :show-badge? true :is-context? true
                                    :is-part-of? true :part-of-sort-idx 9}}
                       false))))
      (is (= {:is-part-of? true :part-of-sort-idx 3} (row (:id book) (:id chapter)))
          "the delete and the re-insert rolled back with it")
      (is (= {:is-part-of? true :part-of-sort-idx 3} (mirror (:id book) (:id chapter)))
          "so the two still agree"))))

(deftest linking-an-item-elsewhere-leaves-its-part-of-edges-alone
  (test-with-reset-db-and-time
    "link-item-to-another-item! rebuilds the whole contexts map, and the table
     from that map -- an edge it isn't about must come through untouched"
    (let [[book chapter] (book-with-chapter 5)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id chapter)}) shelf true)
      (is (= {:is-part-of? true :part-of-sort-idx 5} (row (:id book) (:id chapter)))
          "the untouched edge keeps its row")
      (is (= {:is-part-of? true :part-of-sort-idx 5} (mirror (:id book) (:id chapter)))
          "and its mirror entry")
      (is (= {:is-part-of? false :part-of-sort-idx -1} (row (:id shelf) (:id chapter)))
          "the new relation is a plain one")
      (is (= {:is-part-of? false :part-of-sort-idx -1} (mirror (:id shelf) (:id chapter))))))
  (test-with-reset-db-and-time "re-linking a relation that is already part-of keeps it part-of"
    (let [[book chapter] (book-with-chapter 5)]
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id chapter)}) book false)
      (is (= {:is-part-of? true :part-of-sort-idx 5} (row (:id book) (:id chapter))))
      (is (= {:is-part-of? true :part-of-sort-idx 5} (mirror (:id book) (:id chapter))))))
  (test-with-reset-db-and-time "and an explicit part-of argument sets both sides"
    (let [book (ds/new-context db {:title "Book"})
          chapter (ds/new-item db "Chapter" "" #{(:id book)} nil)]
      (relations/link-item-to-another-item! db
                                            (ds/get-item db {:id (:id chapter)})
                                            book
                                            true
                                            {:is-part-of? true :part-of-sort-idx 8})
      (is (= {:is-part-of? true :part-of-sort-idx 8} (row (:id book) (:id chapter))))
      (is (= {:is-part-of? true :part-of-sort-idx 8} (mirror (:id book) (:id chapter)))))))

(deftest the-mirror-is-rebuilt-out-of-the-table-not-out-of-nothing
  (test-with-reset-db-and-time
    "set-collection-titles-of-new-item reads the part-of columns back off the rows"
    (let [book (ds/new-context db {:title "Book"})
          chapter (ds/new-item db "Chapter" "" #{(:id book)} nil)]
      (jdbc/execute-one! db
                         ["UPDATE relations SET is_part_of = 1, part_of_sort_idx = 4
                           WHERE owner_id = ? AND target_id = ?"
                          (:id book) (:id chapter)])
      (relations/set-collection-titles-of-new-item db (:id chapter))
      (is (= {:is-part-of? true :part-of-sort-idx 4} (mirror (:id book) (:id chapter)))))))

(deftest a-rebuilt-mirror-entry-takes-its-standing-off-the-row
  (test-with-reset-db-and-time
    "an item can have relation rows and no mirror entries for them at all --
     dev-seed inserts relations with raw SQL and never writes the mirror, and the
     human's dev db is full of items in exactly that shape. A title propagation
     rebuilds the missing entry, and must not rebuild it as not-part-of"
    (let [[book chapter] (book-with-chapter 6)]
      (jdbc/execute-one! db
                         ["UPDATE items SET data = '{\"contexts\":{}}' WHERE id = ?" (:id chapter)])
      (relations/update-collection-title-in-collection-items-for-children db
                                                                          (:id book)
                                                                          "Book, renamed"
                                                                          nil)
      (is (= {:is-part-of? true :part-of-sort-idx 6} (mirror (:id book) (:id chapter)))
          "the rebuilt entry says what the row says")
      (is (true? (get-in (ds/get-item db {:id (:id chapter)})
                         [:data :contexts (:id book) :show-badge?]))
          "including the badge, which get-aggregated-contexts filters on -- a nil
           there drops the context out of the item's badges straight away, and
           the next save writes show_badge NULL for real")
      (let [fresh (ds/get-item db {:id (:id chapter)})]
        (relations/set-the-containers-of-item! db fresh (get-in fresh [:data :contexts]) false))
      (is (= {:is-part-of? true :part-of-sort-idx 6} (row (:id book) (:id chapter)))
          "so the next save, which rebuilds the rows out of the mirror, keeps it")
      (is (= 1 (:relations/show_badge
                 (jdbc/execute-one! db
                                    ["SELECT show_badge FROM relations
                                      WHERE owner_id = ? AND target_id = ?"
                                     (:id book) (:id chapter)])))
          "and does not write show_badge NULL back over it")))
  (test-with-reset-db-and-time "a badge that was off stays off"
    (let [[book chapter] (book-with-chapter 6)]
      (jdbc/execute-one! db
                         ["UPDATE relations SET show_badge = 0 WHERE owner_id = ? AND target_id = ?"
                          (:id book) (:id chapter)])
      (jdbc/execute-one! db
                         ["UPDATE items SET data = '{\"contexts\":{}}' WHERE id = ?" (:id chapter)])
      (relations/update-collection-title-in-collection-items-for-children db
                                                                          (:id book)
                                                                          "Book, renamed"
                                                                          nil)
      (is (false? (get-in (ds/get-item db {:id (:id chapter)})
                          [:data :contexts (:id book) :show-badge?]))
          "the row is read, not defaulted"))))

(defn- make-part-of!
  "Make `part` a part of `whole` at sort index `idx`, the way a save from the
   edit modal does."
  [whole part idx]
  (relations/set-the-containers-of-item! db
                                         (ds/get-item db {:id (:id part)})
                                         {(:id whole) {:title (:title whole)
                                                       :show-badge? true
                                                       :is-context? true
                                                       :is-part-of? true
                                                       :part-of-sort-idx idx}}
                                         true))

(defn- wholes-of
  "The ids that own a relation to this item, read off the `relations` table. Not
   off the mirror: the mirror is written after the rows, so an assertion against
   it would pass just as well if the rows had been half rewritten."
  [item]
  (into #{}
        (map :relations/owner_id)
        (jdbc/execute! db ["SELECT owner_id FROM relations WHERE target_id = ?" (:id item)])))

(deftest a-node-may-be-part-of-several-wholes
  (test-with-reset-db-and-time "two wholes over one part are a DAG, not a cycle"
    (let [a (ds/new-context db {:title "A"})
          b (ds/new-context db {:title "B"})
          leaf (ds/new-item db "Leaf" "" #{(:id a) (:id b)} nil)]
      (relations/set-the-containers-of-item!
        db
        (ds/get-item db {:id (:id leaf)})
        {(:id a) {:title "A" :show-badge? true :is-context? true
                  :is-part-of? true :part-of-sort-idx 1}
         (:id b) {:title "B" :show-badge? true :is-context? true
                  :is-part-of? true :part-of-sort-idx 9}}
        false)
      (is (= {:is-part-of? true :part-of-sort-idx 1} (row (:id a) (:id leaf))))
      (is (= {:is-part-of? true :part-of-sort-idx 9} (row (:id b) (:id leaf)))
          "and it sits at a different position under each"))))

(deftest a-part-of-edge-that-would-close-a-loop-is-refused
  (test-with-reset-db-and-time "nothing can be part of itself"
    (let [a (ds/new-context db {:title "A"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"part of itself"
                            (make-part-of! a a 1)))))
  (test-with-reset-db-and-time "nor part of something that is already part of it"
    (let [book (ds/new-context db {:title "Book"})
          chapter (ds/new-context db {:title "Chapter"})]
      (make-part-of! book chapter 1)
      (is (thrown? clojure.lang.ExceptionInfo (make-part-of! chapter book 1)))
      (is (= #{} (wholes-of book)) "and the refused write left the relations alone")))
  (test-with-reset-db-and-time "however long the way round, and the message names the way"
    (let [a (ds/new-context db {:title "A"})
          b (ds/new-context db {:title "B"})
          c (ds/new-context db {:title "C"})]
      (make-part-of! a b 1)
      (make-part-of! b c 1)
      (let [msg (try (make-part-of! c a 1)
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (some? msg) "the write is refused")
        (is (= (str "Refused: this would make a thing part of itself — "
                    "C (" (:id c) ") → A (" (:id a) ") → B (" (:id b) ") → C (" (:id c) ")")
               msg)))))
  (test-with-reset-db-and-time "a long title is trimmed, so the path stays readable"
    (let [long-title (apply str (repeat 20 "long title "))
          a (ds/new-context db {:title long-title})
          b (ds/new-context db {:title "B"})]
      (make-part-of! a b 1)
      (let [msg (try (make-part-of! b a 1)
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (= (str "Refused: this would make a thing part of itself — "
                    "B (" (:id b) ") → long title long title long title long title long title long…"
                    " (" (:id a) ") → B (" (:id b) ")")
               msg)))))
  (test-with-reset-db-and-time "plain relations may go on forming cycles"
    (let [a (ds/new-context db {:title "A"})
          b (ds/new-context db {:title "B"})]
      (relations/set-the-containers-of-item! db a {(:id b) {:title "B" :show-badge? true}} true)
      (relations/set-the-containers-of-item! db b {(:id a) {:title "A" :show-badge? true}} true)
      (is (= #{(:id b)} (wholes-of a)))
      (is (= #{(:id a)} (wholes-of b))))))

(deftest a-roman-numeral-is-not-an-ordering
  (testing "the sibling index is a plain integer -- the roman convention below -1
            belongs to items.sort_idx, and nothing translates it here"
    (is (= -1 (relations/->part-of-sort-idx "iv")))
    (is (= -1 (relations/->part-of-sort-idx Double/NaN))
        "which is what the modal parses a roman numeral into")
    (is (= 4 (relations/->part-of-sort-idx "4")))
    (is (= 4 (relations/->part-of-sort-idx 4)))))

(deftest a-sort-index-that-is-not-a-number-becomes-unset
  (test-with-reset-db-and-time
    "the modal sends NaN for input that is not a number; it must not reach the
     column, and must not reach the JSON either"
    (let [book (ds/new-context db {:title "Book"})
          chapter (ds/new-item db "Chapter" "" #{(:id book)} nil)]
      (relations/set-the-containers-of-item! db
                                             chapter
                                             {(:id book) {:title "Book"
                                                          :show-badge? true
                                                          :is-context? true
                                                          :is-part-of? true
                                                          :part-of-sort-idx Double/NaN}}
                                             false)
      (is (= {:is-part-of? true :part-of-sort-idx -1} (row (:id book) (:id chapter))))
      (is (= {:is-part-of? true :part-of-sort-idx -1} (mirror (:id book) (:id chapter)))
          "reading the mirror back at all means the JSON survived the round trip"))))

;; --- what hierarchy mode lists ----------------------------------------------

(defn- titles-under
  [whole opts]
  (mapv :title (search/search-related-items db "" (:id whole) opts {})))

(deftest hierarchy-mode-lists-the-parts-in-sibling-order
  (test-with-reset-db-and-time "children come out by part_of_sort_idx, non-children not at all"
    (let [book (ds/new-context db {:title "Book"})
          two (ds/new-item db "Two" "" #{(:id book)} nil)
          one (ds/new-item db "One" "" #{(:id book)} nil)
          _loose (ds/new-item db "Merely related" "" #{(:id book)} nil)]
      (make-part-of! book two 2)
      (make-part-of! book one 1)
      (is (= ["One" "Two"] (titles-under book {:hierarchy-mode? true}))
          "in sibling order, and without the item that is only related")
      (is (= #{"One" "Two" "Merely related"} (set (titles-under book {})))
          "while the ordinary list still shows all three"))))

(deftest a-part-nobody-placed-comes-last
  (test-with-reset-db-and-time
    "the unset -1 sorts behind every placed sibling, not ahead of 0"
    (let [book (ds/new-context db {:title "Book"})
          zero (ds/new-item db "Zero" "" #{(:id book)} nil)
          one (ds/new-item db "One" "" #{(:id book)} nil)
          first-made (ds/new-item db "Unplaced, made first" "" #{(:id book)} nil)
          then-made (ds/new-item db "Unplaced, made second" "" #{(:id book)} nil)]
      (make-part-of! book one 1)
      (make-part-of! book zero 0)
      (make-part-of! book first-made -1)
      (make-part-of! book then-made -1)
      ;; Touch the one made first, so that most-recently-touched and
      ;; most-recently-made disagree. Without this the two unplaced siblings come
      ;; out in the same order either way and the assertion below cannot tell the
      ;; tie-break it names from the order they were inserted in.
      (ds/reprioritize-item db {:id (:id first-made)})
      (is (= ["Zero" "One" "Unplaced, made first" "Unplaced, made second"]
             (titles-under book {:hierarchy-mode? true}))
          "the placed ones ascending, then the unplaced ones, most recently touched first"))))

(deftest a-negative-index-other-than-minus-one-is-an-ordinary-index
  (test-with-reset-db-and-time
    "-1 is the only reserved value: it means unplaced and sorts last, while a
     deliberate -2 is a way of saying \"first\" without renumbering the siblings"
    (let [book (ds/new-context db {:title "Book"})
          front (ds/new-item db "Front matter" "" #{(:id book)} nil)
          zero (ds/new-item db "Zero" "" #{(:id book)} nil)
          unplaced (ds/new-item db "Unplaced" "" #{(:id book)} nil)]
      (make-part-of! book zero 0)
      (make-part-of! book front -2)
      (make-part-of! book unplaced -1)
      (is (= ["Front matter" "Zero" "Unplaced"] (titles-under book {:hierarchy-mode? true}))))))

;; --- and what the levels below it list ---------------------------------------

(defn- make-part-of-both!
  "Make `part` a part of two wholes at once. Not two calls to make-part-of!:
   set-the-containers-of-item! rebuilds the item's relations out of the map it
   is handed, so a second call naming only the other whole would drop the first
   edge again."
  [[whole-a idx-a] [whole-b idx-b] part]
  (relations/set-the-containers-of-item!
    db
    (ds/get-item db {:id (:id part)})
    {(:id whole-a) {:title (:title whole-a) :show-badge? true :is-context? true
                    :is-part-of? true :part-of-sort-idx idx-a}
     (:id whole-b) {:title (:title whole-b) :show-badge? true :is-context? true
                    :is-part-of? true :part-of-sort-idx idx-b}}
    false))

(deftest a-level-lists-the-nodes-at-that-depth-in-path-order
  (test-with-reset-db-and-time
    "level 2 is the parts of the parts, ordered by the whole path down to them
     and not by the last step of it -- everything under the first child before
     everything under the second, whatever indices are used further down"
    (let [a (ds/new-context db {:title "A"})
          a1 (ds/new-context db {:title "A1"})
          a2 (ds/new-context db {:title "A2"})
          under (fn [whole titles]
                  (doall (map-indexed (fn [i t]
                                        (let [item (ds/new-item db t "" #{(:id whole)} nil)]
                                          (make-part-of! whole item (inc i))
                                          item))
                                      titles)))]
      (make-part-of! a a1 1)
      (make-part-of! a a2 2)
      (under a1 ["a1-1" "a1-2" "a1-3"])
      (under a2 ["a2-1" "a2-2" "a2-3"])
      (is (= ["A1" "A2"] (titles-under a {:hierarchy-mode? true}))
          "level 1 is still the direct children, and a missing level reads as 1")
      (is (= ["A1" "A2"] (titles-under a {:hierarchy-mode? true :hierarchy-level 1})))
      (is (= ["a1-1" "a1-2" "a1-3" "a2-1" "a2-2" "a2-3"]
             (titles-under a {:hierarchy-mode? true :hierarchy-level 2}))
          "by the tuples (1,1) (1,2) (1,3) (2,1) (2,2) (2,3)")
      (is (= [] (titles-under a {:hierarchy-mode? true :hierarchy-level 3}))
          "and nothing is that deep")))
  (test-with-reset-db-and-time
    "the direct children are not among the level-2 rows -- a level lists the
     nodes at that depth and no other"
    (let [a (ds/new-context db {:title "A"})
          child (ds/new-context db {:title "Child"})
          grandchild (ds/new-item db "Grandchild" "" #{(:id child)} nil)]
      (make-part-of! a child 1)
      (make-part-of! child grandchild 1)
      (is (= ["Grandchild"] (titles-under a {:hierarchy-mode? true :hierarchy-level 2}))))))

(deftest the-unset-rule-holds-at-every-component-of-the-path
  (test-with-reset-db-and-time
    "a child nobody placed sorts behind every placed child at level 1 -- and so
     does everything below it at level 2, where its own -1 is not the last
     component of the path but the first"
    (let [book (ds/new-context db {:title "Book"})
          unplaced (ds/new-context db {:title "Unplaced chapter"})
          placed (ds/new-context db {:title "Placed chapter"})
          from-unplaced (ds/new-item db "Page of the unplaced one" "" #{(:id unplaced)} nil)
          from-placed (ds/new-item db "Page of the placed one" "" #{(:id placed)} nil)]
      (make-part-of! book unplaced -1)
      (make-part-of! book placed 1)
      ;; Both pages carry the same index under their own chapter, so the order
      ;; below can only come from the component above it.
      (make-part-of! unplaced from-unplaced 1)
      (make-part-of! placed from-placed 1)
      (is (= ["Placed chapter" "Unplaced chapter"] (titles-under book {:hierarchy-mode? true})))
      (is (= ["Page of the placed one" "Page of the unplaced one"]
             (titles-under book {:hierarchy-mode? true :hierarchy-level 2}))
          "the unset -1 one component up carried its whole subtree to the back")))
  (test-with-reset-db-and-time
    "and a deliberate -2 one component up carries its subtree to the front, the
     way it does among siblings"
    (let [book (ds/new-context db {:title "Book"})
          front (ds/new-context db {:title "Front matter"})
          one (ds/new-context db {:title "Chapter one"})
          front-page (ds/new-item db "A page of the front matter" "" #{(:id front)} nil)
          first-page (ds/new-item db "A page of chapter one" "" #{(:id one)} nil)]
      (make-part-of! book one 1)
      (make-part-of! book front -2)
      (make-part-of! front front-page 1)
      (make-part-of! one first-page 1)
      (is (= ["A page of the front matter" "A page of chapter one"]
             (titles-under book {:hierarchy-mode? true :hierarchy-level 2}))))))

(deftest a-node-reachable-by-two-paths-is-listed-once-per-path
  (test-with-reset-db-and-time
    "the part-of edges are a DAG, so the same thing can sit at a level by more
     than one route. It is listed at each place it occupies -- deduplicating it
     would throw away one of two positions the human deliberately gave it"
    (let [book (ds/new-context db {:title "Book"})
          one (ds/new-context db {:title "Chapter one"})
          two (ds/new-context db {:title "Chapter two"})
          shared (ds/new-item db "The shared page" "" #{(:id one) (:id two)} nil)
          plain (ds/new-item db "A page of chapter two" "" #{(:id two)} nil)]
      (make-part-of! book one 1)
      (make-part-of! book two 2)
      (make-part-of-both! [one 1] [two 5] shared)
      (make-part-of! two plain 2)
      (is (= ["The shared page" "A page of chapter two" "The shared page"]
             (titles-under book {:hierarchy-mode? true :hierarchy-level 2}))
          "at (1,1) and again at (2,5), with (2,2) between them")
      (is (= 3 (count (titles-under book {:hierarchy-mode? true :hierarchy-level 2})))
          "three rows for two distinct items"))))

(deftest the-depth-below-a-whole-is-how-far-the-stepper-may-go
  (test-with-reset-db-and-time
    "the deepest path down, which is the level past which there is nothing to
     show -- so the stepper can refuse the step rather than offer it and answer
     it with an empty list"
    (let [book (ds/new-context db {:title "Book"})
          chapter (ds/new-context db {:title "Chapter"})
          page (ds/new-context db {:title "Page"})
          loose (ds/new-item db "Merely related" "" #{(:id book)} nil)]
      (is (= 0 (search/part-of-depth db (:id book))) "nothing is a part of it yet")
      (make-part-of! book chapter 1)
      (is (= 1 (search/part-of-depth db (:id book))))
      (make-part-of! chapter page 1)
      (is (= 2 (search/part-of-depth db (:id book))))
      (is (= 1 (search/part-of-depth db (:id chapter))) "counted from the whole asked about")
      (is (= 0 (search/part-of-depth db (:id page))))
      (is (= 0 (search/part-of-depth db (:id loose)))
          "and an item merely related to the book is not below it at all")))
  (test-with-reset-db-and-time
    "the longest path decides it, not the shortest -- a whole with one shallow
     and one deep child goes as deep as the deep one"
    (let [book (ds/new-context db {:title "Book"})
          shallow (ds/new-item db "A page filed straight under the book" "" #{(:id book)} nil)
          chapter (ds/new-context db {:title "Chapter"})
          section (ds/new-context db {:title "Section"})
          page (ds/new-item db "A page of the section" "" #{(:id section)} nil)]
      (make-part-of! book shallow 1)
      (make-part-of! book chapter 2)
      (make-part-of! chapter section 1)
      (make-part-of! section page 1)
      (is (= 3 (search/part-of-depth db (:id book)))))))

(deftest a-part-sits-differently-under-each-of-its-wholes
  (test-with-reset-db-and-time "the sibling index belongs to the edge, not to the node"
    (let [a (ds/new-context db {:title "A"})
          b (ds/new-context db {:title "B"})
          x (ds/new-item db "X" "" #{(:id a) (:id b)} nil)
          y (ds/new-item db "Y" "" #{(:id a) (:id b)} nil)
          under (fn [item a-idx b-idx]
                  (relations/set-the-containers-of-item!
                    db
                    (ds/get-item db {:id (:id item)})
                    {(:id a) {:title "A" :show-badge? true :is-context? true
                              :is-part-of? true :part-of-sort-idx a-idx}
                     (:id b) {:title "B" :show-badge? true :is-context? true
                              :is-part-of? true :part-of-sort-idx b-idx}}
                    false))]
      (under x 1 2)
      (under y 2 1)
      (is (= ["X" "Y"] (titles-under a {:hierarchy-mode? true})))
      (is (= ["Y" "X"] (titles-under b {:hierarchy-mode? true}))))))

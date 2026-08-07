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

(defn- contexts-of [item] (into #{} (keys (get-in (ds/get-item db {:id (:id item)}) [:data :contexts]))))

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
      (is (= #{} (contexts-of book)) "and the refused write left the relations alone")))
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
      (is (= #{(:id b)} (contexts-of a)))
      (is (= #{(:id a)} (contexts-of b))))))

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

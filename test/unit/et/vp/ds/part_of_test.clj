(ns et.vp.ds.part-of-test
  "A part-of relation is written down twice -- as a row in `relations` and as an
   entry in the `contexts` map inside `items.data` -- and the save paths rebuild
   one out of the other. These tests pin the two to each other."
  (:require [clojure.test :refer [deftest is]]
            [et.vp.ds :as ds]
            [et.vp.ds.relations :as relations]
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

(deftest a-sort-index-that-is-not-a-number-becomes-unset
  (test-with-reset-db-and-time
    "the modal sends NaN for input that is neither a number nor a roman numeral;
     it must not reach the column, and must not reach the JSON either"
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

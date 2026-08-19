(ns et.vp.ds.deletion-tombstone-test
  "Deletion as a tombstoning: what a delete writes down before it scraps the row.

   A delete used to leave the history it had already accumulated and take the text
   the item was actually carrying -- so the one version guaranteed to be missing
   from a dead item's history was its last one, and nothing in the table said the
   item had been deleted rather than never written on again. Now the standing text
   goes to the history first, under one more version number, marked.

   What the mark buys is that a version which was superseded by NOTHING can be told
   from one that was superseded by a later text. For an item that is archaeology.
   For a relation it is not: an edge can be unlinked and linked again, the history
   is keyed on the pair, so the mark is where in one list the edge was not there."
  (:require [clojure.test :refer [deftest is]]
            [et.vp.ds :as ds]
            [et.vp.ds.relations :as relations]
            [et.vp.ds.search-test :refer [test-with-reset-db-and-time db]]
            [next.jdbc :as jdbc]))

(defmacro ^:private with-fresh-history
  "`test-with-reset-db-and-time`, and both history tables cleared as well -- see
   the note on the macro of the same name in et.vp.ds.relation-history-test."
  [description & body]
  `(test-with-reset-db-and-time ~description
     (jdbc/execute-one! db ["delete from history"])
     (jdbc/execute-one! db ["delete from relation_history"])
     ~@body))

(defn- item-versions
  [item]
  (:versions (ds/get-description-history db {:id (:id item)})))

(defn- edge-versions
  [item whole]
  (:versions (relations/get-relation-description-history db (:id item) (:id whole))))

(defn- book-with-chapter
  ([] (book-with-chapter ""))
  ([description]
   (let [book (ds/new-context db {:title "Book"})
         chapter (ds/new-item db "Chapter" description #{(:id book)} nil)]
     [book (ds/get-item db {:id (:id chapter)})])))

;; -- the item's own text ------------------------------------------------------

(deftest deleting-an-item-keeps-the-text-it-was-carrying
  (with-fresh-history "the standing description, and the title it stood under"
    (let [[_ chapter] (book-with-chapter)]
      (ds/update-context-description db {:id (:id chapter) :description "what it said"} "app")
      (ds/delete-item db {:id (:id chapter)})
      (is (nil? (:id (ds/get-item db {:id (:id chapter)}))) "precondition: the item is gone")
      (let [versions (item-versions chapter)]
        (is (= ["what it said" nil] (mapv :text versions))
            "the text it went out on is at the head -- which used to be the one
             version a delete was sure to lose. Under it the blank it was created
             with, archived when that first write superseded it")
        (is (= ["Chapter" "Chapter"] (mapv :title versions))
            "each under the title it stood beneath at the time, as archived versions
             of an item's description always are")
        (is (= ["app" "app"] (mapv :source versions))
            "and stamped with the source that WROTE it, not with the delete -- the
             blank one too: it was created through the app like anything else")
        (is (= [true false] (mapv :tombstone versions))
            "and only the version the delete wrote is the deletion")
        (is (not-any? :current versions)
            "no current version: there is no row left for one to be standing in")))))

(deftest the-mark-is-written-even-when-there-was-nothing-to-keep
  (with-fresh-history "an item with an empty description was still an item"
    (let [[_ chapter] (book-with-chapter)]
      (ds/delete-item db {:id (:id chapter)})
      (let [versions (item-versions chapter)]
        (is (= 1 (count versions))
            "one version, and nothing but the delete put it there")
        (is (= [true] (mapv :tombstone versions)))
        (is (= ["Chapter"] (mapv :title versions))
            "and the title says which item this id was")))))

(deftest the-tombstone-is-one-more-version-and-supersedes-nothing
  (with-fresh-history "it counts on from the versions already there, and only it is marked"
    (let [[_ chapter] (book-with-chapter)]
      (ds/update-context-description db {:id (:id chapter) :description "first"} "app")
      (ds/update-context-description db {:id (:id chapter) :description "second"} "api")
      (ds/update-context-description db {:id (:id chapter) :description "third"} "app")
      (ds/delete-item db {:id (:id chapter)})
      (let [versions (item-versions chapter)]
        (is (= ["third" "second" "first" nil] (mapv :text versions))
            "newest first, and the text it went out on is at the head")
        (is (= [4 3 2 1] (mapv :version versions))
            "the delete's version counts on from the three that were already there")
        (is (= [true false false false] (mapv :tombstone versions))
            "and the delete marked its own version only: the ones under it were each
             superseded by a later text, which is a different thing")))))

;; -- the edges that pointed at it ---------------------------------------------

(deftest deleting-an-item-tombstones-the-edges-that-pointed-at-it
  (with-fresh-history "the text on the edge goes the same way the item's own text does"
    (let [[book chapter] (book-with-chapter)]
      (relations/update-relation-description! db (:id chapter) (:id book)
                                              "why it is in this book" "api")
      (ds/delete-item db {:id (:id chapter)})
      (is (nil? (relations/relation-description db (:id chapter) (:id book)))
          "precondition: the edge went with the item")
      (let [versions (edge-versions chapter book)]
        (is (= ["why it is in this book"] (mapv :text versions)))
        (is (= ["api"] (mapv :source versions)))
        (is (= [true] (mapv :tombstone versions))))))
  (with-fresh-history "and an edge nobody wrote on still leaves the cut"
    (let [[book chapter] (book-with-chapter)]
      (ds/delete-item db {:id (:id chapter)})
      (let [versions (edge-versions chapter book)]
        (is (= [nil] (mapv :text versions)))
        (is (= [true] (mapv :tombstone versions))
            "which is the only record that these two items were ever joined"))))
  (with-fresh-history "every edge, and not just one of them"
    (let [[book chapter] (book-with-chapter)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id chapter)}) shelf true)
      (relations/update-relation-description! db (:id chapter) (:id book) "the book edge" "app")
      (relations/update-relation-description! db (:id chapter) (:id shelf) "the shelf edge" "app")
      (ds/delete-item db {:id (:id chapter)})
      (is (= ["the book edge"] (mapv :text (edge-versions chapter book))))
      (is (= ["the shelf edge"] (mapv :text (edge-versions chapter shelf))))
      (is (= [true] (mapv :tombstone (edge-versions chapter shelf))))))
  (with-fresh-history "and the edges that pointed at something else are left alone"
    (let [[book chapter] (book-with-chapter)
          other (ds/new-item db "Chapter two" "" #{(:id book)} nil)]
      (relations/update-relation-description! db (:id other) (:id book) "the edge that stays" "app")
      (ds/delete-item db {:id (:id chapter)})
      (let [versions (edge-versions other book)]
        (is (= ["the edge that stays"] (mapv :text versions)))
        (is (= [true] (mapv :current versions)) "still standing, and so not marked")
        (is (= [false] (mapv :tombstone versions)))))))

(ns et.vp.ds.relation-description-test
  "The body text of a relation, which is the one thing a relation carries that
   does not travel with a list row.

   Everything else about an edge reaches the client eagerly: the annotation is
   projected by the search queries, the badge and the part-of standing come off
   the `contexts` mirror inside items.data, which every row carries. A body of
   text on every row of every list is what this field exists not to be, so it is
   neither projected nor mirrored, and it is read one edge at a time.

   That buys two obligations, and these are them: nothing may load it by
   accident, and nothing may lose it -- least of all the save that rewrites an
   item's relation rows out of a map that has never heard of it."
  (:require [clojure.test :refer [deftest is]]
            [et.vp.ds :as ds]
            [et.vp.ds.relations :as relations]
            [et.vp.ds.search-test :refer [test-with-reset-db-and-time db]]
            [next.jdbc :as jdbc]))

(defn- book-with-chapter
  []
  (let [book (ds/new-context db {:title "Book"})
        chapter (ds/new-item db "Chapter" "" #{(:id book)} nil)]
    [book (ds/get-item db {:id (:id chapter)})]))

(defn- containers-of
  [item]
  (:contexts (:data (ds/get-item db {:id (:id item)}))))

(deftest a-relation-holds-a-body-of-text
  (test-with-reset-db-and-time "written and read back on the edge it was written to"
    (let [[book chapter] (book-with-chapter)]
      (is (nil? (relations/relation-description db (:id chapter) (:id book)))
          "an edge nobody has written on has none, and that is not the empty string")
      (relations/update-relation-description! db (:id chapter) (:id book) "why it is in here")
      (is (= "why it is in here" (relations/relation-description db (:id chapter) (:id book))))))
  (test-with-reset-db-and-time "and it belongs to the edge, not to either end of it"
    (let [[book chapter] (book-with-chapter)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/link-item-to-another-item! db chapter shelf true)
      (relations/update-relation-description! db (:id chapter) (:id book) "as a chapter")
      (relations/update-relation-description! db (:id chapter) (:id shelf) "as a thing on a shelf")
      (is (= "as a chapter" (relations/relation-description db (:id chapter) (:id book))))
      (is (= "as a thing on a shelf" (relations/relation-description db (:id chapter) (:id shelf))))))
  (test-with-reset-db-and-time "an empty string is a cleared text, and is kept as one"
    (let [[book chapter] (book-with-chapter)]
      (relations/update-relation-description! db (:id chapter) (:id book) "something")
      (relations/update-relation-description! db (:id chapter) (:id book) "")
      (is (= "" (relations/relation-description db (:id chapter) (:id book)))))))

(deftest the-text-is-not-in-the-mirror
  (test-with-reset-db-and-time
    "and must never be: the mirror is inside items.data, which every list row
     carries, so a text in there would be loaded with every list there is"
    (let [[book chapter] (book-with-chapter)]
      (relations/update-relation-description! db (:id chapter) (:id book) "not for every row")
      (let [entry (get (containers-of chapter) (:id book))]
        (is (some? entry) "the entry itself is there")
        (is (not (contains? entry :description)))
        (is (not-any? #(= "not for every row" %) (vals entry))
            "under no key at all")))))

(deftest a-save-from-the-edit-modal-does-not-take-the-text-with-it
  (test-with-reset-db-and-time
    "set-containers-of-item! deletes an item's relation rows and re-inserts them
     from the map the client handed back -- and that map cannot carry the text,
     because the client was never given it. Reinstated from the rows being
     replaced, or every save from the edit modal would be a silent delete."
    (let [[book chapter] (book-with-chapter)]
      (relations/update-relation-description! db (:id chapter) (:id book) "survives the rewrite")
      (relations/set-the-containers-of-item! db
                                             (ds/get-item db {:id (:id chapter)})
                                             (containers-of chapter)
                                             false)
      (is (= "survives the rewrite" (relations/relation-description db (:id chapter) (:id book))))))
  (test-with-reset-db-and-time "including a save that changes the edge's part-of standing"
    (let [[book chapter] (book-with-chapter)]
      (relations/update-relation-description! db (:id chapter) (:id book) "still here")
      (relations/set-the-containers-of-item!
        db
        (ds/get-item db {:id (:id chapter)})
        {(:id book) {:title "Book" :show-badge? true :is-context? true
                     :is-part-of? true :part-of-sort-idx 2}}
        false)
      (is (= "still here" (relations/relation-description db (:id chapter) (:id book))))
      (is (= 1 (:relations/is_part_of
                 (jdbc/execute-one! db
                                    ["SELECT is_part_of FROM relations
                                      WHERE owner_id = ? AND target_id = ?"
                                     (:id book) (:id chapter)])))
          "which is what the save was for")))
  (test-with-reset-db-and-time "and a link to somewhere else, which rewrites the same rows"
    (let [[book chapter] (book-with-chapter)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/update-relation-description! db (:id chapter) (:id book) "untouched by the new edge")
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id chapter)}) shelf true)
      (is (= "untouched by the new edge"
             (relations/relation-description db (:id chapter) (:id book))))
      (is (nil? (relations/relation-description db (:id chapter) (:id shelf)))
          "and the new edge starts out with none")))
  (test-with-reset-db-and-time "and an unlink of a different edge"
    (let [[book chapter] (book-with-chapter)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id chapter)}) shelf true)
      (relations/update-relation-description! db (:id chapter) (:id book) "the edge that stays")
      (relations/unlink-item-from-another-item! db (ds/get-item db {:id (:id chapter)}) shelf)
      (is (= "the edge that stays" (relations/relation-description db (:id chapter) (:id book))))))
  (test-with-reset-db-and-time "while unlinking the edge itself takes its text with it, as it must"
    (let [[book chapter] (book-with-chapter)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id chapter)}) shelf true)
      (relations/update-relation-description! db (:id chapter) (:id book) "goes with the edge")
      (relations/unlink-item-from-another-item! db (ds/get-item db {:id (:id chapter)}) book)
      (is (nil? (relations/relation-description db (:id chapter) (:id book)))
          "there is no edge left for it to be about")
      (is (nil? (:relations/id
                  (jdbc/execute-one! db
                                     ["SELECT id FROM relations WHERE owner_id = ? AND target_id = ?"
                                      (:id book) (:id chapter)]))))))
  (test-with-reset-db-and-time "an explicit description in the containers map wins over the row"
    (let [[book chapter] (book-with-chapter)]
      (relations/update-relation-description! db (:id chapter) (:id book) "the old one")
      (relations/set-the-containers-of-item!
        db
        (ds/get-item db {:id (:id chapter)})
        {(:id book) {:title "Book" :show-badge? true :is-context? true :description "the new one"}}
        false)
      (is (= "the new one" (relations/relation-description db (:id chapter) (:id book)))))))

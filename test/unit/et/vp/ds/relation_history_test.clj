(ns et.vp.ds.relation-history-test
  "The versions of the text a relation carries.

   The same mechanism an item's description is versioned by -- the row about to be
   overwritten is archived under the next version number, stamped with the source
   that wrote it -- so most of what is pinned here is that the two really do
   behave alike. What is different is different for a reason, and each of those
   reasons has a test of its own:

   - the history is keyed on the two items, because the relation row's id does not
     survive a save of either item's edit modal;
   - a save that did not change the text does not earn a version, because the
     modal that saves the text also saves a badge and a sibling index;
   - an unlink archives the text it takes away, because that is the one write that
     can destroy a relation's text with nobody having typed over it."
  (:require [clojure.test :refer [deftest is]]
            [et.vp.ds :as ds]
            [et.vp.ds.relations :as relations]
            [et.vp.ds.search-test :refer [test-with-reset-db-and-time db]]
            [next.jdbc :as jdbc]
            [provenance :as provenance]))

(defmacro ^:private with-fresh-history
  "`test-with-reset-db-and-time`, and the relation history cleared as well.

   reset-db stops at `items` and leaves both history tables standing. That is only
   harmless while item ids are never handed back out, and whether they are depends
   on a DDL detail no test can see: SQLite reissues rowids on a table declared
   without AUTOINCREMENT, so on such a database a new item can be born already
   owning an earlier test's versions -- which shows up as a namespace that passes
   alone and fails in the suite. (Cookbook: \"Rhizome tests: reset-db leaves the
   history table, and SQLite reissues item ids\".) Every test below asserts on
   those rows, so every one of them starts from none."
  [description & body]
  `(test-with-reset-db-and-time ~description
     (jdbc/execute-one! db ["delete from relation_history"])
     ~@body))

(defn- book-with-chapter
  []
  (let [book (ds/new-context db {:title "Book"})
        chapter (ds/new-item db "Chapter" "" #{(:id book)} nil)]
    [book (ds/get-item db {:id (:id chapter)})]))

(defn- containers-of
  [item]
  (:contexts (:data (ds/get-item db {:id (:id item)}))))

(defn- history
  [item whole]
  (relations/get-relation-description-history db (:id item) (:id whole)))

(defn- versions
  [item whole]
  (:versions (history item whole)))

(defn- texts
  [item whole]
  (mapv :text (versions item whole)))

(defn- write!
  ([item whole text] (relations/update-relation-description! db (:id item) (:id whole) text))
  ([item whole text source]
   (relations/update-relation-description! db (:id item) (:id whole) text source)))

(defn- archived-rows
  "Straight off the table, so a test can say what was archived without going
   through the read that assembles the answer."
  [item whole]
  (jdbc/execute! db
                 ["SELECT text, version, source FROM relation_history
                   WHERE owner_id = ? AND target_id = ? ORDER BY version"
                  (:id whole) (:id item)]))

;; -- the mechanism, edge for edge with an item's description ------------------

(deftest a-fresh-edge-has-one-version-and-it-is-the-current-one
  (with-fresh-history "an edge nobody has written on: one version, and it is empty"
    (let [[book chapter] (book-with-chapter)
          {:keys [versions total]} (history chapter book)]
      (is (= 1 total))
      (is (= [nil] (mapv :text versions)))
      (is (= [1] (mapv :version versions)))
      (is (true? (:current (first versions)))
          "the head is the version that is standing, which is what the modal edits")
      (is (empty? (archived-rows chapter book))
          "and nothing has been archived, because there was nothing to archive")))
  (with-fresh-history "the first text written is the current version, still version 1"
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "why this chapter is in this book")
      (is (= ["why this chapter is in this book"] (texts chapter book)))
      (is (= [1] (mapv :version (versions chapter book))))
      (is (empty? (archived-rows chapter book))
          "a blank text is not worth a version of its own -- there is nothing in it
           to recover, and every edge would open with one"))))

(deftest replacing-the-text-archives-the-one-it-replaces
  (with-fresh-history "newest first, and the numbers count up"
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "the first thing said")
      (write! chapter book "the second thing said")
      (write! chapter book "the third thing said")
      (is (= ["the third thing said" "the second thing said" "the first thing said"]
             (texts chapter book)))
      (is (= [3 2 1] (mapv :version (versions chapter book))))
      (is (= 3 (:total (history chapter book))))
      (is (= [true nil nil] (mapv :current (versions chapter book)))
          "exactly one of them is the one standing")))
  (with-fresh-history "each archived row carries the source that WROTE it"
    ;; The whole of provenance rests on this. Stamping the archived row with the
    ;; source of the write that superseded it would hand every old version to
    ;; whoever came next, and the ranges would come back well-formed and wrong.
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "his paragraph" "app")
      (write! chapter book "an agent's paragraph" "api")
      (is (= [{:text "an agent's paragraph" :source "api"} {:text "his paragraph" :source "app"}]
             (mapv #(select-keys % [:text :source]) (versions chapter book))))))
  (with-fresh-history "and the archived row keeps its own created_at"
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "one")
      (write! chapter book "two")
      (let [[older newer] (reverse (versions chapter book))]
        (is (string? (:created_at older)) "an archived version says when it was superseded")
        (is (nil? (:created_at newer))
            "and the current one says nothing: a relation has no updated_at of its
             own, and a date borrowed off either item would be about something else")))))

(deftest a-save-that-changed-nothing-earns-no-version
  (with-fresh-history
    "the relation modal writes the text alongside the badge and the sibling index,
     so a save is not evidence of an edit -- ticking a badge four times must not be
     four versions of an unchanged text"
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "written once")
      (dotimes [_ 4] (write! chapter book "written once"))
      (is (= ["written once"] (texts chapter book)))
      (is (empty? (archived-rows chapter book)))))
  (with-fresh-history
    "and it does not re-stamp the source either: a writer who wrote nothing does
     not get to own the text"
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "his paragraph" "app")
      (write! chapter book "his paragraph" "api")
      (is (= [{:text "his paragraph" :source "app"}]
             (mapv #(select-keys % [:text :source]) (versions chapter book))))))
  (with-fresh-history "a write to an edge that does not exist writes nothing at all"
    (let [[book chapter] (book-with-chapter)
          shelf (ds/new-context db {:title "Shelf"})]
      (write! chapter shelf "there is no such edge")
      (is (= {:versions [] :total 0} (history chapter shelf)))
      (is (empty? (archived-rows chapter shelf))))))

(deftest clearing-the-text-is-a-version-like-any-other
  (with-fresh-history
    "the text that was cleared is recoverable, and the empty one is what stands"
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "written by mistake")
      (write! chapter book "")
      (is (= ["" "written by mistake"] (texts chapter book)))
      (is (true? (:current (first (versions chapter book))))
          "the blank version stays at the head. Dropping it -- which is what an
           item's history does -- would leave the modal's editor showing the newest
           version that was not blank, and the next save would put a text the user
           deleted back on the edge")
      (is (= "" (relations/relation-description db (:id chapter) (:id book)))))))

(deftest the-history-belongs-to-the-edge
  (with-fresh-history "two edges of one item have two histories"
    (let [[book chapter] (book-with-chapter)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/link-item-to-another-item! db chapter shelf true)
      (write! chapter book "as a chapter")
      (write! chapter book "as a chapter of this book")
      (write! chapter shelf "as a thing on a shelf")
      (is (= ["as a chapter of this book" "as a chapter"] (texts chapter book)))
      (is (= ["as a thing on a shelf"] (texts chapter shelf)))))
  (with-fresh-history "and the same pair read backwards is not that edge"
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "which way round this runs")
      (write! chapter book "which way round this really runs")
      (is (= 2 (:total (history chapter book))))
      (is (= {:versions [] :total 0} (history book chapter))
          "a relation has a direction, and there is no edge in the other one"))))

;; -- the rewrite: an item's relation rows are replaced wholesale --------------

(deftest the-history-survives-the-rows-being-rewritten
  (with-fresh-history
    "set-containers-of-item! deletes an item's relation rows and re-inserts them,
     so relations.id is not the edge's identity. The history is keyed on the two
     items instead, and this is the test that says so"
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "the first thing said")
      (write! chapter book "the second thing said")
      (let [id-before (:relations/id
                        (jdbc/execute-one! db
                                           ["SELECT id FROM relations
                                             WHERE owner_id = ? AND target_id = ?"
                                            (:id book) (:id chapter)]))]
        (relations/set-the-containers-of-item! db
                                               (ds/get-item db {:id (:id chapter)})
                                               (containers-of chapter)
                                               false)
        (let [id-after (:relations/id
                         (jdbc/execute-one! db
                                            ["SELECT id FROM relations
                                              WHERE owner_id = ? AND target_id = ?"
                                             (:id book) (:id chapter)]))]
          (is (not= id-before id-after)
              "precondition: the row really is a different row now")))
      (is (= ["the second thing said" "the first thing said"] (texts chapter book))
          "and the history came through it whole")))
  (with-fresh-history "the rewrite is not an edit, so it adds no version"
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "the standing text")
      (dotimes [_ 3]
        (relations/set-the-containers-of-item! db
                                               (ds/get-item db {:id (:id chapter)})
                                               (containers-of chapter)
                                               false))
      (is (= ["the standing text"] (texts chapter book)))
      (is (empty? (archived-rows chapter book)))))
  (with-fresh-history
    "and it carries the source across. A text that came back from the rewrite with
     an empty source would read as the owner's own hand -- provenance/source-of
     takes an empty column for his -- so an agent's paragraph would be handed to
     him as his, in an answer that looks entirely well-formed"
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "an agent's paragraph" "api")
      (relations/set-the-containers-of-item! db
                                             (ds/get-item db {:id (:id chapter)})
                                             (containers-of chapter)
                                             false)
      (is (= [{:text "an agent's paragraph" :source "api"}]
             (mapv #(select-keys % [:text :source]) (versions chapter book))))))
  (with-fresh-history "linking somewhere else rewrites the same rows, and is not an edit"
    (let [[book chapter] (book-with-chapter)
          shelf (ds/new-context db {:title "Shelf"})]
      (write! chapter book "before the new edge" "api")
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id chapter)}) shelf true)
      (is (= [{:text "before the new edge" :source "api"}]
             (mapv #(select-keys % [:text :source]) (versions chapter book))))
      (is (= [nil] (texts chapter shelf)) "and the new edge starts out with nothing on it"))))

(deftest unlinking-keeps-what-it-takes-away
  (with-fresh-history
    "the row goes and the text goes with it, so this is the one write that can
     destroy a relation's text without anyone having typed over it. Archived on the
     way out, or the field is not versioned at all"
    (let [[book chapter] (book-with-chapter)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id chapter)}) shelf true)
      (write! chapter book "why it was ever in this book" "api")
      (relations/unlink-item-from-another-item! db (ds/get-item db {:id (:id chapter)}) book)
      (is (nil? (relations/relation-description db (:id chapter) (:id book)))
          "precondition: the edge is gone")
      (let [{:keys [versions total]} (history chapter book)]
        (is (= 1 total))
        (is (= ["why it was ever in this book"] (mapv :text versions)))
        (is (= ["api"] (mapv :source versions)) "with the source it was written by")
        (is (not-any? :current versions)
            "and no current version at the head: there is no text standing on an
             edge that is not there"))))
  (with-fresh-history "re-linking starts blank, on top of what was kept"
    (let [[book chapter] (book-with-chapter)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id chapter)}) shelf true)
      (write! chapter book "said the first time round")
      (relations/unlink-item-from-another-item! db (ds/get-item db {:id (:id chapter)}) book)
      (relations/link-item-to-another-item! db
                                            (ds/get-item db {:id (:id chapter)})
                                            (ds/get-item db {:id (:id book)})
                                            true)
      (is (= [nil "said the first time round"] (texts chapter book))
          "which is what happened: nothing is written on it now, and something was")
      (is (true? (:current (first (versions chapter book)))))))
  (with-fresh-history "unlinking an edge nobody wrote on archives nothing"
    (let [[book chapter] (book-with-chapter)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id chapter)}) shelf true)
      (relations/unlink-item-from-another-item! db (ds/get-item db {:id (:id chapter)}) book)
      (is (= {:versions [] :total 0} (history chapter book)))))
  (with-fresh-history "and unlinking one edge leaves the other one's history alone"
    (let [[book chapter] (book-with-chapter)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id chapter)}) shelf true)
      (write! chapter book "the edge that stays")
      (write! chapter book "the edge that stays, revised")
      (write! chapter shelf "the edge that goes")
      (relations/unlink-item-from-another-item! db (ds/get-item db {:id (:id chapter)}) shelf)
      (is (= ["the edge that stays, revised" "the edge that stays"] (texts chapter book)))
      (is (= ["the edge that goes"] (texts chapter shelf))))))

;; -- provenance over a relation's history ------------------------------------

(deftest provenance-of-a-relations-text
  (with-fresh-history "his lines and an agent's, told apart within one edge's text"
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "his line" "app")
      (write! chapter book "his line\nan agent's line\nand another" "api")
      (let [{:keys [legend ranges]} (provenance/of-relation db (:id chapter) (:id book))]
        (is (= provenance/legend legend) "the server's own sentence, and only one of them")
        (is (= [{:from 1 :to 1 :caution 1.0} {:from 2 :to 3 :caution 0.0}] ranges)))))
  (with-fresh-history
    "the history is folded oldest-first. Handed over the way it arrives it would
     say the agent wrote his line -- a well-formed answer with the value inverted,
     which is the failure et.vp.ds's own history has a test for too"
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "his one\nhis two\nhis three" "app")
      (write! chapter book "their one\ntheir two\ntheir three" "api")
      (is (= [{:from 1 :to 3 :caution 0.0}]
             (:ranges (provenance/of-relation db (:id chapter) (:id book)))))))
  (with-fresh-history "an edge with no text has nothing to attribute"
    (let [[book chapter] (book-with-chapter)]
      (is (nil? (provenance/of-relation db (:id chapter) (:id book))))))
  (with-fresh-history "nor has one whose text was cleared, though it has a history"
    (let [[book chapter] (book-with-chapter)]
      (write! chapter book "written and then removed" "app")
      (write! chapter book "" "app")
      (is (some? (seq (versions chapter book))) "precondition: there is a history")
      (is (nil? (provenance/of-relation db (:id chapter) (:id book)))
          "ranges over a text that is not there would answer a question nobody asked")))
  (with-fresh-history "a text written before the source column existed reads as his"
    ;; The rows in the owner's database predate description_source, so this is the
    ;; shape almost every relation in it is in.
    (let [[book chapter] (book-with-chapter)]
      (jdbc/execute-one! db
                         ["UPDATE relations SET description = ?, description_source = NULL
                           WHERE owner_id = ? AND target_id = ?"
                          "written before there was a source column\nsecond line"
                          (:id book) (:id chapter)])
      (is (= [{:from 1 :to 2 :caution 1.0}]
             (:ranges (provenance/of-relation db (:id chapter) (:id book)))))))
  (with-fresh-history "and the edge it is asked about is the edge it answers about"
    (let [[book chapter] (book-with-chapter)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/link-item-to-another-item! db chapter shelf true)
      (write! chapter book "his line" "app")
      (write! chapter shelf "an agent's line" "api")
      (is (= [{:from 1 :to 1 :caution 1.0}]
             (:ranges (provenance/of-relation db (:id chapter) (:id book)))))
      (is (= [{:from 1 :to 1 :caution 0.0}]
             (:ranges (provenance/of-relation db (:id chapter) (:id shelf))))))))

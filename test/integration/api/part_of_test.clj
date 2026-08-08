(ns api.part-of-test
  "The /ui half of the acyclicity guarantee. The SPA carries the edit modal's
   save through `update-item`, so that is where a part-of cycle has to be
   refused -- and the refusal has to come back as a normal response, naming the
   loop, rather than as a thrown error the modal cannot show."
  (:require [clojure.test :refer [deftest is]]
            [api.harness :refer [call!]]
            [api.helpers :refer [with-fresh-db]]
            [et.vp.ds :as ds]
            [et.vp.ds.relations :as relations]
            [et.vp.ds.search :as search]
            [et.vp.ds.search-test :refer [db]]
            [next.jdbc :as jdbc]))

(defn- save-relations!
  "What the edit modal sends: the item as the left-hand column has it, and the
   whole related-contexts map as the right-hand column has it."
  [item contexts]
  (call! :update-item
         {}
         {:context {:context {:id (:id item)
                              :title (:title item)
                              :short_title (:short_title item)
                              :annotation nil
                              :tags nil
                              :data {:highlighted-secondary-contexts []}}}
          :item-contexts contexts}))

(defn- part-of-entry
  [whole idx]
  {(:id whole) {:title (:title whole)
                :show-badge? true
                :is-context? true
                :is-part-of? true
                :part-of-sort-idx idx}})

(defn- containers-of
  "The ids that own a relation to this item, read off the `relations` table. Not
   off the mirror: the mirror is written after the rows, so an assertion against
   it would pass just as well if the rows had been half rewritten."
  [item]
  (into #{}
        (map :relations/owner_id)
        (jdbc/execute! db ["SELECT owner_id FROM relations WHERE target_id = ?" (:id item)])))

(deftest saving-a-part-of-relation-through-the-modal-test
  (with-fresh-db "the flag and the sibling index come back on the item"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          chapter (ds/new-item db "Chapter" "" #{(:id book)} nil)
          resp (save-relations! chapter (part-of-entry book 2))]
      (is (nil? (:part-of-refused resp)))
      (is (and (contains? resp :modal) (nil? (:modal resp)))
          "a save that went through closes the modal it came from")
      (is (= {:is-part-of? true :part-of-sort-idx 2}
             (-> (:selected-item resp)
                 (get-in [:data :contexts (:id book)])
                 (select-keys [:is-part-of? :part-of-sort-idx])))))))

(deftest hierarchy-mode-lists-only-the-parts-test
  (with-fresh-db "selecting a context in hierarchy mode answers with its parts, in sibling order"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          two (ds/new-item db "Two" "" #{(:id book)} nil)
          one (ds/new-item db "One" "" #{(:id book)} nil)
          _loose (ds/new-item db "Merely related" "" #{(:id book)} nil)]
      (save-relations! two (part-of-entry book 2))
      (save-relations! one (part-of-entry book 1))
      (is (= ["One" "Two"]
             (mapv :title (:items (call! :fetch-context
                                         {:hierarchy-mode? true}
                                         [{:id (:id book)} false]))))
          "hierarchy mode is session state, so it rides in on the request")
      (is (= #{"One" "Two" "Merely related"}
             (set (mapv :title (:items (call! :fetch-context {} [{:id (:id book)} false])))))
          "and without it the ordinary related-items list is unchanged"))))

(deftest hierarchy-mode-answers-at-the-level-it-is-asked-for-test
  (with-fresh-db
    "the level is session state the same way the mode is, so it rides in on the
     request -- and how deep the context goes rides back with the list, because
     the strip cannot bound its stepper without being told"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          {one :selected-item} (call! :insert-context nil {:title "Chapter one"})
          {two :selected-item} (call! :insert-context nil {:title "Chapter two"})
          page-of-one (ds/new-item db "A page of chapter one" "" #{(:id one)} nil)
          page-of-two (ds/new-item db "A page of chapter two" "" #{(:id two)} nil)
          select (fn [state] (call! :fetch-context state [{:id (:id book)} false]))
          at (fn [n] {:hierarchy-mode? true :hierarchy-level {:context (:id book) :level n}})]
      (save-relations! one (part-of-entry book 1))
      (save-relations! two (part-of-entry book 2))
      (save-relations! page-of-one (part-of-entry one 1))
      (save-relations! page-of-two (part-of-entry two 1))
      (let [resp (select {:hierarchy-mode? true})]
        (is (= ["Chapter one" "Chapter two"] (mapv :title (:items resp)))
            "no level asked for is level 1, what the mode listed before there were levels")
        (is (= {:context (:id book) :level 2} (:hierarchy-max-level resp))
            "and the strip is told where to stop, and for which context"))
      (is (= ["A page of chapter one" "A page of chapter two"]
             (mapv :title (:items (select (at 2)))))
          "level 2 is the parts of the parts, in path order")
      (is (= [] (mapv :title (:items (select (at 3)))))
          "and past the deepest path there is nothing -- which is what the stepper
           is bounded so as not to ask")
      (is (= ["Chapter one" "Chapter two"]
             (mapv :title (:items (select {:hierarchy-mode? true
                                           :hierarchy-level {:context (:id one) :level 2}}))))
          "a level counted under another context is not this one's level, so the
           reading starts again at the first")
      (let [resp (select {})]
        (is (= #{"Chapter one" "Chapter two"} (set (mapv :title (:items resp))))
            "without the mode the ordinary related-items list is unchanged")
        (is (nil? (:hierarchy-max-level resp))
            "and the subgraph is not walked for an answer nobody is going to read"))
      ))
  (with-fresh-db
    "the bound comes back counted with the same q the list was filtered by --
     the whole point of it is that a step is never offered into an empty list,
     and a search only a level-1 row answers is where an unfiltered bound would
     offer one"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          {chapter :selected-item} (call! :insert-context nil {:title "Chapter"})
          page (ds/new-item db "A page of the chapter" "" #{(:id chapter)} nil)
          appendix (ds/new-item db "Appendix, unfiled" "" #{(:id book)} nil)
          list! (fn [q] (call! :list-resources
                               {:hierarchy-mode? true
                                :hierarchy-level {:context (:id book) :level 1}
                                :selected-item {:id (:id book)}
                                :q q}))]
      (save-relations! chapter (part-of-entry book 1))
      (save-relations! page (part-of-entry chapter 1))
      (save-relations! appendix (part-of-entry book -1))
      (let [resp (list! nil)]
        (is (= ["Chapter" "Appendix, unfiled"] (mapv :title (:items resp))))
        (is (= {:context (:id book) :level 2} (:hierarchy-max-level resp))
            "unfiltered, the tree is two deep and the step down is there to offer"))
      (let [resp (list! "Appendix")]
        (is (= ["Appendix, unfiled"] (mapv :title (:items resp)))
            "the hierarchy list is filtered by q like any other item search")
        (is (= {:context (:id book) :level 1} (:hierarchy-max-level resp))
            "so the bound beside it is 1, and the step down is not offered")))))

(deftest a-row-says-which-whole-it-is-filed-under-test
  (with-fresh-db
    "the annotation and the sibling index a row carries belong to the edge that
     put it at this level, and below level 1 that is not the edge between the row
     and the selected context. So the row carries the other end of it too --
     without which anything acting on what the card shows has to guess, and the
     guess that was there to be made was the selected context"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          {chapter :selected-item} (call! :insert-context nil {:title "Chapter"})
          page (ds/new-item db "Page" "" #{(:id chapter)} nil)
          at (fn [n] {:hierarchy-mode? true :hierarchy-level {:context (:id book) :level n}})
          select (fn [state] (call! :fetch-context state [{:id (:id book)} false]))]
      (save-relations! chapter (part-of-entry book 1))
      (save-relations! page (part-of-entry chapter 1))
      (is (= [(:id book)] (mapv :part_of_whole_id (:items (select (at 1)))))
          "at level 1 that is the selected context, which is what it always was")
      (is (= [(:id chapter)] (mapv :part_of_whole_id (:items (select (at 2)))))
          "at level 2 it is the chapter -- the row is shown under the book and
           filed under the chapter, and the second is the one it says")
      (is (every? #(nil? (:part_of_whole_id %)) (:items (call! :fetch-context {}
                                                               [{:id (:id book)} false])))
          "the ordinary related-items list has no such edge to name"))))

(deftest unlink-aims-at-the-whole-it-is-given-test
  (with-fresh-db
    "the row's own whole, not the selected context. Below level 1 those are two
     different edges, and the one the user is pointing at is the row's"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          {chapter :selected-item} (call! :insert-context nil {:title "Chapter"})
          ;; Also plainly related to the book, which is the shape in which
          ;; unlinking from the selected context did something destructive: it
          ;; deleted an edge that was nowhere on the screen.
          page (ds/new-item db "Page" "" #{(:id chapter) (:id book)} nil)
          wholes-of (fn [item]
                      (into #{}
                            (map :relations/owner_id)
                            (jdbc/execute! db
                                           ["SELECT owner_id FROM relations WHERE target_id = ?"
                                            (:id item)])))]
      (save-relations! chapter (part-of-entry book 1))
      (relations/set-the-containers-of-item!
        db
        (ds/get-item db {:id (:id page)})
        {(:id chapter) {:title "Chapter" :show-badge? true :is-context? true
                        :is-part-of? true :part-of-sort-idx 1}
         (:id book) {:title "Book" :show-badge? true :is-context? true}}
        false)
      (is (= #{(:id chapter) (:id book)} (wholes-of page)))
      (call! :unlink-item
             {:selected-item book :hierarchy-mode? true
              :hierarchy-level {:context (:id book) :level 2}}
             (ds/get-item db {:id (:id page)})
             {:id (:id chapter) :title "Chapter"})
      (is (= #{(:id book)} (wholes-of page))
          "the edge the row was shown by is the one that went, and the plain
           relation to the selected context -- which is not what was pointed at,
           and is what used to be deleted instead -- is still there")))
  (with-fresh-db
    "with no whole named it is the selected context, which is what every caller
     before this meant and what every list but hierarchy mode's still means"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          {shelf :selected-item} (call! :insert-context nil {:title "Shelf"})
          item (ds/new-item db "Item" "" #{(:id book) (:id shelf)} nil)]
      (call! :unlink-item {:selected-item book} (ds/get-item db {:id (:id item)}))
      (is (= #{(:id shelf)}
             (into #{}
                   (map :relations/owner_id)
                   (jdbc/execute! db
                                  ["SELECT owner_id FROM relations WHERE target_id = ?"
                                   (:id item)])))))))

(deftest annotating-a-deep-edge-does-not-move-the-selection-test
  (with-fresh-db
    "the annotation goes to the edge the row was shown by, and the list that
     comes back is still the selected context's. Those were one thing while the
     modal was always handed the selected context; below level 1 they are two,
     and answering an annotation edit by navigating to the whole that owns the
     edge is not what the user asked for"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          {chapter :selected-item} (call! :insert-context nil {:title "Chapter"})
          page (ds/new-item db "Page" "" #{(:id chapter) (:id book)} nil)
          annotation-of (fn [whole]
                          (:relations/annotation
                            (jdbc/execute-one! db
                                               ["SELECT annotation FROM relations
                                                 WHERE owner_id = ? AND target_id = ?"
                                                (:id whole) (:id page)])))]
      (save-relations! chapter (part-of-entry book 1))
      (relations/set-the-containers-of-item!
        db
        (ds/get-item db {:id (:id page)})
        {(:id chapter) {:title "Chapter" :show-badge? true :is-context? true
                        :is-part-of? true :part-of-sort-idx 1}
         (:id book) {:title "Book" :show-badge? true :is-context? true}}
        false)
      (let [resp (call! :update-annotations
                        {:selected-item book
                         :hierarchy-mode? true
                         :hierarchy-level {:context (:id book) :level 2}}
                        {:item-id (:id page)
                         :context-id (:id chapter)
                         :relation-annotation "written from level 2"})]
        (is (= "written from level 2" (annotation-of chapter))
            "the edge on screen is the one written to")
        (is (nil? (annotation-of book))
            "and the plain relation to the selected context is left alone")
        (is (= (:id book) (:id (:selected-item resp)))
            "the selection is where it was -- the whole that owns the edge is not
             where the user is standing")
        (is (= ["Page"] (mapv :title (:items resp)))
            "and the list is still level 2 of it")))))

(deftest a-hierarchy-list-is-bounded-even-when-the-caller-names-no-bound-test
  (with-fresh-db
    "the SPA asks with no limit of its own -- it always has -- and that was safe
     while a list was as long as the relations somebody typed. A level is as long
     as the paths into it, and paths multiply with depth. So the bound is the
     query's rather than the caller's, and there is no way to ask without it"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          {one :selected-item} (call! :insert-context nil {:title "Chapter one"})
          {two :selected-item} (call! :insert-context nil {:title "Chapter two"})
          ;; The shared page is a part of both chapters, so it reaches level 2 by
          ;; two routes. Two rows for one item, plus one more, is three rows at a
          ;; level whose two parents are only two -- which is the property the
          ;; bound has to be counted in rows for.
          shared (ds/new-item db "The shared page" "" #{(:id one) (:id two)} nil)
          own (ds/new-item db "A page of chapter one" "" #{(:id one)} nil)
          _loose (mapv #(ds/new-item db (str "Merely related " %) "" #{(:id book)} nil) [1 2])
          at (fn [n] {:hierarchy-mode? true :hierarchy-level {:context (:id book) :level n}})
          select (fn [state] (call! :fetch-context state [{:id (:id book)} false]))]
      (save-relations! one (part-of-entry book 1))
      (save-relations! two (part-of-entry book 2))
      (save-relations! own (part-of-entry one 2))
      (relations/set-the-containers-of-item!
        db
        (ds/get-item db {:id (:id shared)})
        (into {}
              (map (fn [chapter]
                     [(:id chapter) {:title (:title chapter) :show-badge? true :is-context? true
                                     :is-part-of? true :part-of-sort-idx 1}]))
              [one two])
        false)
      (is (= ["The shared page" "A page of chapter one" "The shared page"]
             (mapv :title (:items (select (at 2)))))
          "three rows for two items, because the level is as long as its paths")
      (with-redefs [search/max-part-of-rows 2]
        (is (= ["The shared page" "A page of chapter one"] (mapv :title (:items (select (at 2)))))
            "the list stops at the bound without the caller having asked for one,
             keeping the front of the level in path order rather than a sample")
        (is (= 2 (count (:items (select (at 1)))))
            "level 1 is the two chapters, under the bound, so it is whole")
        (is (= 4 (count (:items (call! :fetch-context {} [{:id (:id book)} false]))))
            "and the ordinary related-items list is over the bound and untouched
             by it -- its length is what somebody typed, which is why it never
             needed one")))))

(deftest a-save-that-fails-for-any-other-reason-is-reported-too-test
  (with-fresh-db
    "the save takes a write transaction, so another writer holding the database
     makes it fail -- and a failure that only throws leaves the SPA's go-block in
     an error it has no branch for: modal open, :loading never cleared, nothing
     said. It has to come back in band like the refusal does"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          chapter (ds/new-item db "Chapter" "" #{(:id book)} nil)
          resp (with-redefs [et.vp.ds.relations/set-the-containers-of-item!
                               (fn [& _]
                                 (throw (java.sql.SQLException.
                                          "[SQLITE_BUSY] The database file is locked")))]
                 (save-relations! chapter (part-of-entry book 1)))]
      (is (= "[SQLITE_BUSY] The database file is locked" (:save-failed resp))
          "the reason comes back rather than being thrown past the client")
      (is (= :edit-context (:modal resp))
          "and the modal stays open, with everything typed still in it")
      (is (nil? (:part-of-refused resp))
          "it is not dressed up as a refusal -- the user cannot correct this one"))))

(deftest a-cycle-is-refused-on-ui-test
  (with-fresh-db "the refusal names the path and reopens the modal"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          {chapter :selected-item} (call! :insert-context nil {:title "Chapter"})]
      (save-relations! chapter (part-of-entry book 1))
      (let [resp (save-relations! book (part-of-entry chapter 1))]
        (is (= (str "Refused: this would make a thing part of itself — "
                    "Chapter (" (:id chapter) ") → Book (" (:id book) ")"
                    " → Chapter (" (:id chapter) ")")
               (:part-of-refused resp))
            "the loop is named, both ways round")
        (is (= :edit-context (:modal resp))
            "and the modal the edit was made in comes back")
        (is (= #{} (containers-of book))
            "nothing was written: the refused save left the relations as they were")
        (is (= #{(:id book)} (containers-of chapter))
            "including the edge that was already there")))))

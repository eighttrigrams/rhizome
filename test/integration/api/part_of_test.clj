(ns api.part-of-test
  "The /ui half of the acyclicity guarantee. The SPA carries the edit modal's
   save through `update-item`, so that is where a part-of cycle has to be
   refused -- and the refusal has to come back as a normal response, naming the
   loop, rather than as a thrown error the modal cannot show."
  (:require [clojure.test :refer [deftest is]]
            [api.harness :refer [call!]]
            [api.helpers :refer [with-fresh-db]]
            [et.vp.ds :as ds]
            [et.vp.ds.relations]
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
          select (fn [state] (call! :fetch-context state [{:id (:id book)} false]))]
      (save-relations! one (part-of-entry book 1))
      (save-relations! two (part-of-entry book 2))
      (save-relations! page-of-one (part-of-entry one 1))
      (save-relations! page-of-two (part-of-entry two 1))
      (let [resp (select {:hierarchy-mode? true})]
        (is (= ["Chapter one" "Chapter two"] (mapv :title (:items resp)))
            "no level asked for is level 1, what the mode listed before there were levels")
        (is (= 2 (:hierarchy-max-level resp)) "and the strip is told where to stop"))
      (is (= ["A page of chapter one" "A page of chapter two"]
             (mapv :title (:items (select {:hierarchy-mode? true :hierarchy-level 2}))))
          "level 2 is the parts of the parts, in path order")
      (is (= [] (mapv :title (:items (select {:hierarchy-mode? true :hierarchy-level 3}))))
          "and past the deepest path there is nothing -- which is what the stepper
           is bounded so as not to ask")
      (let [resp (select {})]
        (is (= #{"Chapter one" "Chapter two"} (set (mapv :title (:items resp))))
            "without the mode the ordinary related-items list is unchanged")
        (is (nil? (:hierarchy-max-level resp))
            "and the subgraph is not walked for an answer nobody is going to read")))))

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

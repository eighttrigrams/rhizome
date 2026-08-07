(ns api.part-of-test
  "The /ui half of the acyclicity guarantee. The SPA carries the edit modal's
   save through `update-item`, so that is where a part-of cycle has to be
   refused -- and the refusal has to come back as a normal response, naming the
   loop, rather than as a thrown error the modal cannot show."
  (:require [clojure.test :refer [deftest is]]
            [api.harness :refer [call!]]
            [api.helpers :refer [with-fresh-db]]
            [et.vp.ds :as ds]
            [et.vp.ds.search-test :refer [db]]))

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
  [item]
  (into #{} (keys (get-in (ds/get-item db {:id (:id item)}) [:data :contexts]))))

(deftest saving-a-part-of-relation-through-the-modal-test
  (with-fresh-db "the flag and the sibling index come back on the item"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          chapter (ds/new-item db "Chapter" "" #{(:id book)} nil)
          resp (save-relations! chapter (part-of-entry book 2))]
      (is (nil? (:part-of-refused resp)))
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

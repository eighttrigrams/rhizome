(ns api.relation-description-test
  "The /ui half of the relation's body text: the one read that fetches it, the
   save that writes it, and the guarantee that nothing else does either.

   The guarantee is the point of the feature. A relation's text is prose, and
   there is one per edge; a list is a hundred rows and every row is an edge. So
   it is read on its own, when a pointer comes to rest on the strip that shows
   the relation, or when the modal that edits it opens -- and the tests that
   matter most here are the ones that show the *other* commands answering
   without it."
  (:require [clojure.test :refer [deftest is]]
            [api.harness :refer [call!]]
            [api.helpers :refer [with-fresh-db]]
            [et.vp.ds :as ds]
            [et.vp.ds.relations :as relations]
            [et.vp.ds.search-test :refer [db]]))

(def ^:private marker
  "Distinctive enough that finding it anywhere in a response is proof, not
   coincidence."
  "SENTINEL-relation-body-text-42")

(defn- book-with-chapter!
  []
  (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
        chapter (ds/new-item db "Chapter" "" #{(:id book)} nil)]
    [book (ds/get-item db {:id (:id chapter)})]))

(defn- fetch!
  [item whole]
  (:relation-description (call! :fetch-relation-description
                                {}
                                {:item-id (:id item) :context-id (:id whole)})))

(defn- save!
  [item whole extra]
  (call! :update-annotations
         {:selected-item whole}
         (merge {:item-id (:id item) :context-id (:id whole)} extra)))

(deftest fetching-one-relations-text-test
  (with-fresh-db "the read answers with the text and with the edge it is about"
    (let [[book chapter] (book-with-chapter!)]
      (relations/update-relation-description! db (:id chapter) (:id book) marker)
      (is (= {:item-id (:id chapter) :context-id (:id book) :text marker}
             (fetch! chapter book))
          "the edge is named in the answer, because the pointer has moved on by
           the time it lands and the client has to know which one answered")))
  (with-fresh-db "an edge nobody has written on answers with nil, not with a blank"
    (let [[book chapter] (book-with-chapter!)]
      (is (= {:item-id (:id chapter) :context-id (:id book) :text nil} (fetch! chapter book)))))
  (with-fresh-db "and so does a pair of items with no edge between them at all"
    (let [[book chapter] (book-with-chapter!)
          shelf (ds/new-context db {:title "Shelf"})]
      (is (nil? (:text (fetch! chapter shelf))))
      (is (nil? (:text (fetch! book chapter)))
          "including the same edge read backwards -- a relation has a direction")))
  (with-fresh-db "the read leaves the selection and the list where they were"
    (let [[book chapter] (book-with-chapter!)
          resp (call! :fetch-relation-description
                      {:selected-item book :items [{:id (:id chapter)}]}
                      {:item-id (:id chapter) :context-id (:id book)})]
      (is (= (:id book) (:id (:selected-item resp))))
      (is (= [{:id (:id chapter)}] (:items resp))
          "it runs on hover, so it must not move anything"))))

(deftest saving-one-relations-text-test
  (with-fresh-db "the modal's save writes it to the edge the card was shown by"
    (let [[book chapter] (book-with-chapter!)]
      (save! chapter book {:relation-description marker})
      (is (= marker (relations/relation-description db (:id chapter) (:id book))))))
  (with-fresh-db "an empty string clears it -- that is a text the user removed"
    (let [[book chapter] (book-with-chapter!)]
      (relations/update-relation-description! db (:id chapter) (:id book) marker)
      (save! chapter book {:relation-description ""})
      (is (= "" (relations/relation-description db (:id chapter) (:id book))))))
  (with-fresh-db
    "a save that does not carry the key leaves it alone. The modal sends the key
     only once the fetch that fills its textarea has landed, so its absence means
     'not loaded' -- and writing an unloaded field would be the one way a lazily
     fetched text can be lost"
    (let [[book chapter] (book-with-chapter!)]
      (relations/update-relation-description! db (:id chapter) (:id book) marker)
      (save! chapter book {:relation-annotation "an annotation, and nothing else"})
      (is (= marker (relations/relation-description db (:id chapter) (:id book))))))
  (with-fresh-db "it goes to the one edge, and not to the item's other ones"
    (let [[book chapter] (book-with-chapter!)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/link-item-to-another-item! db chapter shelf true)
      (save! chapter book {:relation-description marker})
      (is (= marker (relations/relation-description db (:id chapter) (:id book))))
      (is (nil? (relations/relation-description db (:id chapter) (:id shelf))))))
  (with-fresh-db "and it survives the standing being saved alongside it"
    (let [[book chapter] (book-with-chapter!)]
      (save! chapter
             book
             {:relation-description marker
              :relation-annotation "noted"
              :relation-standing {:show-badge? false :is-part-of? true :part-of-sort-idx 3}})
      (is (= marker (relations/relation-description db (:id chapter) (:id book))))
      (is (= marker (:text (fetch! chapter book))) "and reads back through the fetch")))
  (with-fresh-db
    "a refused save writes no text either -- the standing is written first
     precisely so that a refusal there leaves everything after it unwritten"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          {chapter :selected-item} (call! :insert-context nil {:title "Chapter"})]
      (relations/set-the-containers-of-item!
        db
        (ds/get-item db {:id (:id chapter)})
        {(:id book) {:title "Book" :show-badge? true :is-context? true
                     :is-part-of? true :part-of-sort-idx 1}}
        true)
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id book)}) chapter true)
      (let [resp (save! book
                        chapter
                        {:relation-description marker
                         :relation-standing {:show-badge? true :is-part-of? true
                                             :part-of-sort-idx 1}})]
        (is (re-find #"part of itself" (:part-of-refused resp)))
        (is (nil? (relations/relation-description db (:id book) (:id chapter)))
            "nothing was saved, and that includes the text")))))

(deftest the-text-is-never-loaded-with-a-list-test
  (with-fresh-db
    "no command that builds a list carries it. This is the whole reason the field
     is not projected and not mirrored, and it is the assertion that will catch it
     being quietly added to a projection later: the marker must appear in no
     response but the one read that is for it."
    (let [[book chapter] (book-with-chapter!)]
      (relations/update-relation-description! db (:id chapter) (:id book) marker)
      (doseq [[label resp]
                [["the initial listing" (call! :list-resources {})]
                 ["the item search" (call! :list-resources {:active-search :items :q ""})]
                 ["the context search" (call! :list-resources {:active-search :contexts :q ""})]
                 ["selecting the context" (call! :fetch-context {} [{:id (:id book)} false])]
                 ["selecting the item" (call! :fetch-context {} [{:id (:id chapter)} true])]
                 ["the item's own description fetch"
                  (call! :fetch-item-description {} {:id (:id chapter)})]
                 ["the aggregated contexts"
                  (call! :fetch-aggregated-contexts {:selected-item book})]
                 ["a save from the relation modal"
                  (save! chapter book {:relation-annotation "still no body text back"})]]]
        (is (not (re-find (re-pattern marker) (pr-str resp)))
            (str "the relation's text came back with " label)))
      (is (re-find (re-pattern marker) (pr-str (fetch! chapter book)))
          "while the read that is for it does answer with it -- so the sweep above
           is testing something")))
  (with-fresh-db
    "nor does it reach the client on the item, where the mirror everything else
     about an edge travels in would put it"
    (let [[book chapter] (book-with-chapter!)]
      (relations/update-relation-description! db (:id chapter) (:id book) marker)
      (let [item (ds/get-item db {:id (:id chapter)})]
        (is (not (re-find (re-pattern marker) (pr-str (:data item)))))
        (is (some? (get-in item [:data :contexts (:id book)]))
            "though the edge is in the mirror -- it is the text that is not")))))

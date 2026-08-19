(ns api.relation-history-test
  "The /ui half of a relation's version history: the read the modal opens with,
   the read the Provenance button makes, and what the modal's save does to the
   history.

   The datastore mechanism is pinned next door, in
   et.vp.ds.relation-history-test. What is only here is the shape the client is
   answered in -- which version the editor is filled from, which edge an answer is
   about, and the guarantee that a version list, texts and all, never rides in on
   anything else."
  (:require [clojure.test :refer [deftest is]]
            [api.harness :refer [call!]]
            [api.helpers :refer [with-fresh-db]]
            [et.vp.ds :as ds]
            [et.vp.ds.relations :as relations]
            [et.vp.ds.search-test :refer [db]]
            [provenance :as provenance]))

(defn- book-with-chapter!
  []
  (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
        chapter (ds/new-item db "Chapter" "" #{(:id book)} nil)]
    [book (ds/get-item db {:id (:id chapter)})]))

(defn- history!
  [item whole]
  (:relation-history (call! :fetch-relation-history
                            {}
                            {:item-id (:id item) :context-id (:id whole)})))

(defn- provenance!
  [item whole]
  (:relation-provenance (call! :fetch-relation-provenance
                               {}
                               {:item-id (:id item) :context-id (:id whole)})))

(defn- save!
  [item whole extra]
  (call! :update-annotations
         {:selected-item whole}
         (merge {:item-id (:id item) :context-id (:id whole)} extra)))

(defn- write!
  ([item whole text] (relations/update-relation-description! db (:id item) (:id whole) text))
  ([item whole text source]
   (relations/update-relation-description! db (:id item) (:id whole) text source)))

(defn- stored-versions
  [item whole]
  (:versions (relations/get-relation-description-history db (:id item) (:id whole))))

;; -- the read the modal opens with -------------------------------------------

(deftest fetching-one-relations-history-test
  (with-fresh-db "the versions, newest first, with the text that is standing beside them"
    (let [[book chapter] (book-with-chapter!)]
      (write! chapter book "the first thing said")
      (write! chapter book "the second thing said")
      (let [{:keys [item-id context-id text versions total]} (history! chapter book)]
        (is (= [(:id chapter) (:id book)] [item-id context-id])
            "the edge is named in the answer, because the pointer -- or the next
             click -- has moved on by the time it lands")
        (is (= "the second thing said" text))
        (is (= 2 total))
        (is (= ["the second thing said" "the first thing said"] (mapv :text versions)))
        (is (= [2 1] (mapv :version versions)))
        (is (= [true nil] (mapv :current versions))))))
  (with-fresh-db "an edge nobody has written on: one version, and no text"
    (let [[book chapter] (book-with-chapter!)
          {:keys [text versions total]} (history! chapter book)]
      (is (nil? text) "nil, not \"\" -- the modal reads a nil as \"not loaded yet\"")
      (is (= 1 total))
      (is (= [nil] (mapv :text versions)))))
  (with-fresh-db "a text that was cleared is the text that is standing, and it is empty"
    (let [[book chapter] (book-with-chapter!)]
      (write! chapter book "written by mistake")
      (write! chapter book "")
      (let [{:keys [text versions]} (history! chapter book)]
        (is (= "" text)
            "and not the newest thing in the history: the editor is filled from
             this, and filling it from the version underneath would put a text the
             user deleted back on the edge at the next save")
        (is (= ["" "written by mistake"] (mapv :text versions))))))
  (with-fresh-db "an edge that has been unlinked answers with its archive and no standing text"
    (let [[book chapter] (book-with-chapter!)
          shelf (ds/new-context db {:title "Shelf"})]
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id chapter)}) shelf true)
      (write! chapter book "why it was ever in this book")
      (relations/unlink-item-from-another-item! db (ds/get-item db {:id (:id chapter)}) book)
      (let [{:keys [text versions]} (history! chapter book)]
        (is (nil? text) "there is no text standing on an edge that is not there")
        (is (= ["why it was ever in this book"] (mapv :text versions)))
        (is (not-any? :current versions)))))
  (with-fresh-db "a pair of items with no edge between them at all"
    (let [[book chapter] (book-with-chapter!)
          shelf (ds/new-context db {:title "Shelf"})]
      (is (= {:item-id (:id chapter) :context-id (:id shelf) :text nil :versions [] :total 0}
             (history! chapter shelf)))
      (is (= 0 (:total (history! book chapter)))
          "including the same edge read backwards -- a relation has a direction")))
  (with-fresh-db "the read leaves the selection and the list where they were"
    (let [[book chapter] (book-with-chapter!)
          resp (call! :fetch-relation-history
                      {:selected-item book :items [{:id (:id chapter)}]}
                      {:item-id (:id chapter) :context-id (:id book)})]
      (is (= (:id book) (:id (:selected-item resp))))
      (is (= [{:id (:id chapter)}] (:items resp))
          "it is a query, and the modal is open over the list it must not move"))))

;; -- the Provenance button ---------------------------------------------------

(deftest fetching-one-relations-provenance-test
  (with-fresh-db "the text that is standing, and the ranges over it, in one answer"
    (let [[book chapter] (book-with-chapter!)]
      (write! chapter book "his line" "app")
      (write! chapter book "his line\nan agent's line\nand another" "api")
      (let [{:keys [item-id context-id description caution]} (provenance! chapter book)]
        (is (= [(:id chapter) (:id book)] [item-id context-id]))
        (is (= "his line\nan agent's line\nand another" description)
            "the text comes back with the ranges, because the ranges index the lines
             of one exact text and two fetches a save apart would tint every line
             with its neighbour's colour")
        (is (= provenance/legend (:legend caution)))
        (is (= [{:from 1 :to 1 :caution 1.0} {:from 2 :to 3 :caution 0.0}] (:ranges caution))))))
  (with-fresh-db "an edge with nothing written on it has nothing to attribute"
    (let [[book chapter] (book-with-chapter!)
          {:keys [description caution]} (provenance! chapter book)]
      (is (nil? description))
      (is (nil? caution) "and says so with a nil rather than with an empty range list")))
  (with-fresh-db "the ranges are the same ones the item's own provenance is built from"
    ;; Not a second implementation: provenance/of-relation is of-versions over a
    ;; relation's history, and this is what says the two answers are comparable.
    (let [[book chapter] (book-with-chapter!)]
      (write! chapter book "his line" "app")
      (write! chapter book "his line\nan agent's line\nand another" "api")
      (is (= (provenance/of-relation db (:id chapter) (:id book))
             (:caution (provenance! chapter book))))))
  (with-fresh-db "the read leaves the selection and the list where they were"
    (let [[book chapter] (book-with-chapter!)
          resp (call! :fetch-relation-provenance
                      {:selected-item book :items [{:id (:id chapter)}]}
                      {:item-id (:id chapter) :context-id (:id book)})]
      (is (= (:id book) (:id (:selected-item resp))))
      (is (= [{:id (:id chapter)}] (:items resp))))))

;; -- what the modal's save does to the history -------------------------------

(deftest the-modals-save-earns-one-version-and-only-when-it-wrote-test
  (with-fresh-db "a save that changes the text: one version, stamped as the owner's own hand"
    (let [[book chapter] (book-with-chapter!)]
      (save! chapter book {:relation-description "why this chapter is in this book"})
      (let [versions (stored-versions chapter book)]
        (is (= ["why this chapter is in this book"] (mapv :text versions)))
        (is (= ["app"] (mapv :source versions))
            "the modal is the person sitting in front of it, and provenance reads
             \"app\" as his"))))
  (with-fresh-db "two saves, two versions, newest first"
    (let [[book chapter] (book-with-chapter!)]
      (save! chapter book {:relation-description "said once"})
      (save! chapter book {:relation-description "said twice"})
      (is (= ["said twice" "said once"] (mapv :text (stored-versions chapter book))))
      (is (= [2 1] (mapv :version (stored-versions chapter book))))))
  (with-fresh-db
    "a save that only ticks the badge earns nothing. The modal saves the badge, the
     part-of tick and two annotations alongside the text, so a save is not evidence
     that anything was written"
    (let [[book chapter] (book-with-chapter!)]
      (save! chapter book {:relation-description "written once"})
      (dotimes [_ 3]
        (save! chapter
               book
               {:relation-description "written once"
                :relation-annotation "and annotated repeatedly"
                :relation-standing {:show-badge? false :is-part-of? false :part-of-sort-idx -1}}))
      (is (= ["written once"] (mapv :text (stored-versions chapter book)))
          "one version, not four")))
  (with-fresh-db "a save that does not carry the text at all leaves the history alone"
    (let [[book chapter] (book-with-chapter!)]
      (write! chapter book "the standing text")
      (save! chapter book {:relation-annotation "an annotation, and nothing else"})
      (is (= ["the standing text"] (mapv :text (stored-versions chapter book))))))
  (with-fresh-db "clearing it from the modal is a version, and the empty one is what stands"
    (let [[book chapter] (book-with-chapter!)]
      (save! chapter book {:relation-description "written by mistake"})
      (save! chapter book {:relation-description ""})
      (let [{:keys [text versions]} (history! chapter book)]
        (is (= "" text))
        (is (= ["" "written by mistake"] (mapv :text versions))))))
  (with-fresh-db
    "a refused save earns no version either -- the standing is written first
     precisely so that a refusal there leaves everything after it unwritten"
    (let [{book :selected-item} (call! :insert-context nil {:title "Book"})
          {chapter :selected-item} (call! :insert-context nil {:title "Chapter"})]
      (relations/set-the-containers-of-item!
        db
        (ds/get-item db {:id (:id chapter)})
        {(:id book) {:title "Book" :show-badge? true :is-context? true :is-part-of? true
                     :part-of-sort-idx 1}}
        true)
      (relations/link-item-to-another-item! db (ds/get-item db {:id (:id book)}) chapter true)
      (relations/update-relation-description! db (:id book) (:id chapter) "the standing text")
      (let [resp (save! book
                        chapter
                        {:relation-description "typed but never saved"
                         :relation-standing {:show-badge? true :is-part-of? true
                                             :part-of-sort-idx 1}})]
        (is (re-find #"part of itself" (:part-of-refused resp)))
        (is (= ["the standing text"] (mapv :text (stored-versions book chapter)))
            "nothing was saved, and that includes not archiving the text as if
             something had been")))))

;; -- and none of it rides in on anything else --------------------------------

(def ^:private archived-marker
  "Distinctive enough that finding it anywhere is proof rather than coincidence."
  "SENTINEL-superseded-relation-text-17")

(deftest a-version-list-is-never-loaded-with-a-list-test
  (with-fresh-db
    "the history is a text per version, and a list is a hundred edges. Nothing that
     builds one may carry it -- and a SUPERSEDED text is the thing that could most
     easily start travelling unnoticed, because unlike the current one it is in no
     projection anybody looks at"
    (let [[book chapter] (book-with-chapter!)]
      (write! chapter book archived-marker)
      (write! chapter book "what stands now")
      (doseq [[label resp]
                [["the initial listing" (call! :list-resources {})]
                 ["the item search" (call! :list-resources {:active-search :items :q ""})]
                 ["the context search" (call! :list-resources {:active-search :contexts :q ""})]
                 ["selecting the context" (call! :fetch-context {} [{:id (:id book)} false])]
                 ["selecting the item" (call! :fetch-context {} [{:id (:id chapter)} true])]
                 ["the item's own description fetch"
                  (call! :fetch-item-description {} {:id (:id chapter)})]
                 ["the item's own provenance"
                  (call! :fetch-item-provenance {} {:id (:id chapter)})]
                 ["the aggregated contexts" (call! :fetch-aggregated-contexts
                                                   {:selected-item book})]
                 ["the relation's current text"
                  (call! :fetch-relation-description
                         {}
                         {:item-id (:id chapter) :context-id (:id book)})]
                 ["a save from the relation modal"
                  (save! chapter book {:relation-annotation "no history back, please"})]]]
        (is (not (re-find (re-pattern archived-marker) (pr-str resp)))
            (str "a superseded version of the relation's text came back with " label)))
      (is (re-find (re-pattern archived-marker) (pr-str (history! chapter book)))
          "while the read that is for it does answer with it -- so the sweep above is
           testing something")))
  (with-fresh-db "nor does the history reach the client on the item, where the mirror does"
    (let [[book chapter] (book-with-chapter!)]
      (write! chapter book archived-marker)
      (write! chapter book "what stands now")
      (let [item (ds/get-item db {:id (:id chapter)})]
        (is (not (re-find (re-pattern archived-marker) (pr-str (:data item)))))
        (is (some? (get-in item [:data :contexts (:id book)]))
            "though the edge is in the mirror -- it is the history that is not")))))

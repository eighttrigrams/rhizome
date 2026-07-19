(ns api.items-test
  (:require [clojure.test :refer [deftest is]]
            [api.harness :refer [call!]]
            [api.helpers :refer [with-fresh-db]]
            [et.vp.ds :as ds]
            [et.vp.ds.search-test :refer [db]]
            [next.jdbc :as jdbc]))

(defn- relations-count [target-id owner-id]
  (-> (jdbc/execute-one!
        db ["SELECT COUNT(*) AS c FROM relations WHERE target_id = ? AND owner_id = ?"
            target-id owner-id])
      :c))

(deftest insert-item-test
  (with-fresh-db "creates a new item under :selected-item"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          resp (call! :insert-item {:selected-item ctx} {:title "The Prize"})]
      (is (= (:id ctx) (:id (:selected-item resp))))
      (is (false? (:item-view? resp)))
      (is (some #(= "The Prize" (:title %)) (:items resp))))))

(deftest delete-item-test
  (with-fresh-db "removes the item from the database"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          item (ds/new-item db "Doomed" "d" #{(:id ctx)} 1)]
      (call! :delete-item {:selected-item ctx} item)
      (is (nil? (:id (ds/get-item db {:id (:id item)})))))))

(deftest update-item-test
  (with-fresh-db "renames an existing item"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          item (ds/new-item db "Old Title" "ot" #{(:id ctx)} 1)
          resp (call! :update-item {} {:context (assoc item :title "New Title")
                                       :item-contexts {(:id ctx) {:show-badge? true}}})]
      (is (= "New Title" (-> resp :selected-item :title))))))

(deftest reprioritize-item-test
  (with-fresh-db "returns :items and clears :q / :active-search"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          item (ds/new-item db "Sapiens" "s" #{(:id ctx)} 1)
          resp (call! :reprioritize-item {} item)]
      (is (sequential? (:items resp)))
      (is (nil? (:q resp)))
      (is (nil? (:active-search resp))))))

(deftest unlink-item-test
  (with-fresh-db "removes the item -> selected-item relation when others remain"
    (let [{a :selected-item} (call! :insert-context nil {:title "A"})
          {b :selected-item} (call! :insert-context nil {:title "B"})
          item (ds/new-item db "Sapiens" "s" #{(:id a) (:id b)} 1)
          full (ds/get-item db {:id (:id item)})]
      (call! :unlink-item {:selected-item a} full)
      (is (zero? (relations-count (:id item) (:id a))))
      (is (= 1 (relations-count (:id item) (:id b)))))))

(deftest unlink-selected-item-from-container-test
  (with-fresh-db "drops the relation between :selected-item and :old-selected-item"
    (let [{a :selected-item} (call! :insert-context nil {:title "A"})
          {b :selected-item} (call! :insert-context nil {:title "B"})
          item (ds/new-item db "Sapiens" "s" #{(:id a) (:id b)} 1)
          full (ds/get-item db {:id (:id item)})
          resp (call! :unlink-selected-item-from-container
                       {:selected-item full :old-selected-item a})]
      (is (= "A" (-> resp :selected-item :title))
          "navigates back to the old-selected-item")
      (is (false? (:item-view? resp)))
      (is (zero? (relations-count (:id item) (:id a)))))))

(deftest finish-linking-item-test
  (with-fresh-db "links a chosen item id to the currently selected item"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          item-a (ds/new-item db "A" "a" #{(:id ctx)} 1)
          {other-ctx :selected-item} (call! :insert-context nil {:title "Other"})
          item-b (ds/new-item db "B" "b" #{(:id other-ctx)} 1)
          _ (call! :finish-linking-item
                   {:selected-item (ds/get-item db {:id (:id item-a)})}
                   (:id item-b) false false)]
      (is (pos? (relations-count (:id item-b) (:id item-a)))
          "after linking, item-b has item-a among its owners"))))

(deftest fetch-item-description-test
  (with-fresh-db "returns the item's description history"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          item (ds/new-item db "Sapiens" "s" #{(:id ctx)} 1)
          resp (call! :fetch-item-description {} {:id (:id item)})]
      (is (sequential? (:item-descriptions resp)))
      (is (true? (:ignore-item-description resp))
          "no description set yet, so the flag is true"))))

(deftest description-history-keeps-all-revisions-test
  (with-fresh-db "keeps every revision instead of trimming to the newest 5"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          item (ds/new-item db "Sapiens" "s" #{(:id ctx)} 1)]
      (doseq [n (range 1 8)]
        (ds/update-context-description db {:id (:id item) :description (str "d" n)}))
      (let [{:keys [versions total]} (ds/get-description-history db {:id (:id item)})]
        (is (= 7 total))
        (is (= "d7" (:text (first versions))))
        (is (= "d1" (:text (last versions))))))))

(deftest update-annotations-global-test
  (with-fresh-db "writes :annotation onto the item"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          item (ds/new-item db "Sapiens" "s" #{(:id ctx)} 1)
          _ (call! :update-annotations {} {:item-id (:id item) :global-annotation "noted"})
          fresh (ds/get-item db {:id (:id item)})]
      (is (= "noted" (:annotation fresh))))))

(deftest update-annotations-relation-test
  (with-fresh-db "writes a relation-level annotation onto the relations row"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          item (ds/new-item db "Sapiens" "s" #{(:id ctx)} 1)
          _ (call! :update-annotations {} {:item-id (:id item)
                                           :context-id (:id ctx)
                                           :relation-annotation "rel-note"})
          row (jdbc/execute-one!
                db ["SELECT annotation FROM relations WHERE target_id = ? AND owner_id = ?"
                    (:id item) (:id ctx)])]
      (is (= "rel-note" (:relations/annotation row))))))

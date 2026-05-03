(ns api.contexts-test
  (:require [clojure.test :refer [deftest is]]
            [api.harness :refer [call!]]
            [api.helpers :refer [with-fresh-db]]
            [et.vp.ds :as ds]
            [et.vp.ds.search-test :refer [db]]
            [next.jdbc :as jdbc]))

(deftest insert-context-test
  (with-fresh-db "creates a new context and returns it as :selected-item"
    (let [resp (call! :insert-context nil {:title "Books"})
          ctx  (:selected-item resp)]
      (is (= "Books" (:title ctx)))
      (is (true? (:is_context ctx)))
      (is (= [] (:items resp)))
      (is (= '() (:aggregated-contexts resp)))
      (is (= :items (:active-search resp))))))

(deftest fetch-context-as-context-test
  (with-fresh-db "loads a context with its related items"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})]
      (ds/new-item db "Sapiens" "s" #{(:id ctx)} 1)
      (let [resp (call! :fetch-context {} [{:id (:id ctx)} false])]
        (is (= "Books" (-> resp :selected-item :title)))
        (is (some #(= "Sapiens" (:title %)) (:items resp)))
        (is (sequential? (:item-descriptions resp)))
        (is (false? (:unassigned-secondary-contexts-selected? resp)))))))

(deftest fetch-context-as-item-test
  (with-fresh-db "loads a non-context item view (fetch-as-item? = true)"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          item (ds/new-item db "Sapiens" "s" #{(:id ctx)} 1)
          resp (call! :fetch-context {} [{:id (:id item)} true])]
      (is (= "Sapiens" (-> resp :selected-item :title))))))

(deftest deselect-context-test
  (with-fresh-db "returns the global view (no selected-item)"
    (call! :insert-context nil {:title "Books"})
    (let [resp (call! :deselect-context {})]
      (is (nil? (:selected-item resp)))
      (is (sequential? (:items resp)))
      (is (some #(= "Books" (:title %)) (:contexts resp))))))

(deftest delete-context-without-old-selected-item-test
  (with-fresh-db "deletes the context and returns the global view"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})]
      (let [resp (call! :delete-context {} ctx)]
        (is (nil? (:selected-item resp)))
        (is (false? (:item-view? resp)))
        (is (not-any? #(= "Books" (:title %)) (:contexts resp))
            "the deleted context disappears from :contexts")
        (is (nil? (:id (ds/get-item db {:id (:id ctx)}))))))))

(deftest delete-context-with-old-selected-item-test
  (with-fresh-db "deletes the context and navigates back to old-selected-item"
    (let [{a :selected-item} (call! :insert-context nil {:title "Container"})
          {b :selected-item} (call! :insert-context nil {:title "Doomed"})
          resp (call! :delete-context {:old-selected-item a} b)]
      (is (= "Container" (-> resp :selected-item :title)))
      (is (false? (:item-view? resp)))
      (is (nil? (:id (ds/get-item db {:id (:id b)})))))))

(deftest upgrade-item-to-context-test
  (with-fresh-db "flips an item's is_context flag (item -> context)"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          item (ds/new-item db "Sapiens" "s" #{(:id ctx)} 1)
          resp (call! :upgrade-item-to-context {:selected-item item})]
      (is (true? (:is_context (:selected-item resp)))))))

(deftest select-last-context-test
  (with-fresh-db "with old-selected-item, restores it as the selected one"
    (let [{a :selected-item} (call! :insert-context nil {:title "A"})
          resp (call! :select-last-context {:old-selected-item a})]
      (is (= "A" (-> resp :selected-item :title)))
      (is (false? (:item-view? resp)))))
  (with-fresh-db "without old-selected-item, returns an empty map"
    (is (= {} (call! :select-last-context {})))))

(deftest link-selected-context-to-context-test
  (with-fresh-db "links the selected context as a child of another context"
    (let [{a :selected-item} (call! :insert-context nil {:title "Outer"})
          {b :selected-item} (call! :insert-context nil {:title "Inner"})
          _ (call! :link-selected-context-to-context {:selected-item b} a false false)
          n (-> (jdbc/execute-one!
                  db ["SELECT COUNT(*) AS c FROM relations WHERE owner_id = ? AND target_id = ?"
                      (:id a) (:id b)])
                :c)]
      (is (= 1 n) "a relation owner=Outer target=Inner exists"))))

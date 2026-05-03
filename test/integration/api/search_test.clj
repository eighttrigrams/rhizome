(ns api.search-test
  "Covers list-resources branches plus the secondary-context filtering /
   search-mode helpers that share the same /api dispatch entry-point."
  (:require [clojure.test :refer [deftest is]]
            [api.harness :refer [call!]]
            [api.helpers :refer [with-fresh-db]]
            [et.vp.ds :as ds]
            [et.vp.ds.search-test :refer [db]]
            [next.jdbc :as jdbc]))

(deftest list-resources-no-cmd-test
  (with-fresh-db "returns both :items and :contexts in the global view"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})]
      (ds/new-item db "Sapiens" "s" #{(:id ctx)} 1)
      (let [resp (call! :list-resources {})]
        (is (some #(= "Books" (:title %)) (:contexts resp)))
        (is (some #(= "Sapiens" (:title %)) (:items resp)))))))

(deftest list-resources-active-search-items-test
  (with-fresh-db ":active-search :items returns items only"
    (call! :insert-context nil {:title "Books"})
    (let [resp (call! :list-resources {:active-search :items})]
      (is (contains? resp :items))
      (is (not (contains? resp :contexts))))))

(deftest list-resources-active-search-contexts-test
  (with-fresh-db ":active-search :contexts returns contexts only"
    (call! :insert-context nil {:title "Books"})
    (let [resp (call! :list-resources {:active-search :contexts})]
      (is (contains? resp :contexts))
      (is (not (contains? resp :items))))))

(deftest list-resources-start-context-search-test
  (with-fresh-db ":cmd :start-context-search switches active-search to :contexts"
    (call! :insert-context nil {:title "Books"})
    (let [resp (call! :list-resources {:cmd :start-context-search})]
      (is (= :contexts (:active-search resp)))
      (is (= "" (:q resp)))
      (is (contains? resp :contexts)))))

(deftest list-resources-link-item-to-selected-item-test
  (with-fresh-db ":cmd :link-item-to-selected-item flips into linking mode"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          resp (call! :list-resources {:cmd :link-item-to-selected-item
                                       :selected-item ctx})]
      (is (= :items (:active-search resp)))
      (is (true? (:link-item resp))))))

(deftest list-resources-update-context-description-test
  (with-fresh-db ":cmd :update-context-description writes a description"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          resp (call! :list-resources {:cmd :update-context-description
                                       :arg {:id (:id ctx) :description "the lore"}})]
      (is (= "the lore" (-> resp :selected-item :description)))
      (is (sequential? (:item-descriptions resp)))
      (is (= 0 (:description-version-idx resp))))))

(deftest fetch-aggregated-contexts-test
  (with-fresh-db "returns the secondary contexts aggregated from related items"
    (let [{a :selected-item} (call! :insert-context nil {:title "A"})
          {b :selected-item} (call! :insert-context nil {:title "B"})]
      (ds/new-item db "Cross" "c" #{(:id a) (:id b)} 1)
      (let [resp (call! :fetch-aggregated-contexts {:selected-item a})]
        (is (sequential? resp)
            "aggregated-contexts comes back as a seq")))))

(deftest cycle-search-mode-test
  (with-fresh-db "advances the context's search-mode and returns it"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          resp (call! :cycle-search-mode {:selected-item ctx})
          mode (-> resp :selected-item :data :views :current :search-mode)]
      (is (= 1 mode))
      (is (sequential? (:items resp))))))

(deftest change-description-filter-test
  (with-fresh-db "persists description-filter and refreshes :items"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          ctx' (-> ctx
                   (assoc-in [:data :views :current :description-filter] "needle"))
          resp (call! :change-description-filter {:selected-item ctx' :q nil})]
      (is (contains? resp :items)))))

(deftest change-secondary-contexts-selection-test
  (with-fresh-db "returns refreshed :items"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})]
      (ds/new-item db "Sapiens" "s" #{(:id ctx)} 1)
      (let [resp (call! :change-secondary-contexts-selection {:selected-item ctx :q nil})]
        (is (sequential? (:items resp)))))))

(deftest change-secondary-contexts-unassigned-selected-test
  (with-fresh-db "returns refreshed :items"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          resp (call! :change-secondary-contexts-unassigned-selected
                       {:selected-item ctx :q nil})]
      (is (contains? resp :items)))))

(deftest change-secondary-contexts-inverted-test
  (with-fresh-db "returns refreshed :items"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          resp (call! :change-secondary-contexts-inverted {:selected-item ctx :q nil})]
      (is (contains? resp :items)))))

(deftest deselect-secondary-contexts-test
  (with-fresh-db "clears every secondary-contexts knob on :selected-item"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          ctx' (-> ctx
                   (assoc-in [:data :views :current :selected-secondary-contexts] [1 2])
                   (assoc-in [:data :views :current :secondary-contexts-inverted] true))
          resp (call! :deselect-secondary-contexts {:selected-item ctx' :q nil})
          current (-> resp :selected-item :data :views :current)]
      (is (= [] (:selected-secondary-contexts current)))
      (is (false? (:secondary-contexts-inverted current)))
      (is (false? (:secondary-contexts-unassigned-selected current)))
      (is (= 0 (:search-mode current)))
      (is (false? (:notes-mode current)))
      (is (nil? (:description-filter current)))
      (is (sequential? (:items resp)))
      (is (sequential? (:contexts resp))))))

(deftest hidden-context-respects-flag-test
  (with-fresh-db "list-resources hides contexts marked hide_in_global_search"
    (let [{visible :selected-item} (call! :insert-context nil {:title "Visible"})
          {hidden  :selected-item} (call! :insert-context nil {:title "Hidden"})]
      (jdbc/execute-one! db ["UPDATE items SET hide_in_global_search = true WHERE id = ?"
                             (:id hidden)])
      (let [titles (set (map :title (:contexts (call! :list-resources {}))))]
        (is (contains? titles "Visible"))
        (is (not (contains? titles "Hidden"))
            (str "expected Hidden to be filtered out; got " titles))
        (is (some? visible))))))

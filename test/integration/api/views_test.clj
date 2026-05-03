(ns api.views-test
  (:require [clojure.test :refer [deftest is]]
            [api.harness :refer [call!]]
            [api.helpers :refer [with-fresh-db]]
            [et.vp.ds :as ds]
            [et.vp.ds.search-test :refer [db]]))

(deftest store-current-view-test
  (with-fresh-db "appends a stored entry under :data :views :stored"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          ctx' (-> ctx
                   (assoc-in [:data :views :current :search-mode] 2)
                   (assoc-in [:data :views :current :selected-secondary-contexts] [42]))
          ;; persist :current first so store-current-view captures something useful
          _ (ds/update-item db ctx')
          resp (call! :store-current-view {:selected-item (ds/get-item db {:id (:id ctx)})}
                                          {:title "snap"})
          stored (-> resp :selected-item :data :views :stored)]
      (is (= 1 (count stored)))
      (is (= "snap" (-> stored first :title)))
      (is (= 2 (-> stored first :view :search-mode))))))

(deftest load-stored-context-test
  (with-fresh-db "loads stored[idx] back into :data :views :current"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          ctx' (-> ctx
                   (assoc-in [:data :views :current :search-mode] 3))
          _ (ds/update-item db ctx')
          ctx-loaded (ds/get-item db {:id (:id ctx)})
          _ (call! :store-current-view {:selected-item ctx-loaded} {:title "snap"})
          ctx-after-store (ds/get-item db {:id (:id ctx)})
          ;; mutate :current so load can be observed reverting it
          ctx-mutated (assoc-in ctx-after-store
                                [:data :views :current :search-mode] 0)
          _ (ds/update-item db ctx-mutated)
          resp (call! :load-stored-context
                       {:selected-item (ds/get-item db {:id (:id ctx)})}
                       0)]
      (is (= 3 (-> resp :selected-item :data :views :current :search-mode)))
      (is (sequential? (:items resp))))))

(deftest remove-stored-context-test
  (with-fresh-db "drops stored[idx] from :data :views :stored"
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          fresh (ds/get-item db {:id (:id ctx)})
          _ (call! :store-current-view {:selected-item fresh} {:title "one"})
          _ (call! :store-current-view {:selected-item (ds/get-item db {:id (:id ctx)})}
                                       {:title "two"})
          resp (call! :remove-stored-context
                       {:selected-item (ds/get-item db {:id (:id ctx)})}
                       0)
          stored (-> resp :selected-item :data :views :stored)]
      (is (= 1 (count stored)))
      (is (= "two" (-> stored first :title))))))

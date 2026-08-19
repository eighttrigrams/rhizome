(ns dev-seed-test
  "What the seed leaves behind has to be readable by the app that reads it.

   A relation is written down twice -- as a row in `relations` and as an entry in
   the `contexts` map inside the part's items.data -- and everything in the UI
   reads the second one: the badges under a card, the related contexts in the
   item's edit modal, the aggregated contexts. The seed wrote only the row, so
   the demo articles sat under Articles wearing no badge and offering no relation
   to edit."
  (:require [clojure.test :refer [deftest is]]
            [dev-seed :as dev-seed]
            [et.vp.ds :as ds]
            [et.vp.ds.search-test :refer [db test-with-reset-db-and-time]]
            [next.jdbc :as jdbc]))

(defn- articles-id
  []
  (:items/id (jdbc/execute-one! db
                                ["SELECT id FROM items WHERE is_context = 1 AND title = 'Articles'"])))

(deftest a-seeded-relation-is-written-to-both-representations
  (test-with-reset-db-and-time
    "the row and the mirror agree, so what the seed files under Articles is what
     the app shows under it"
    (is (= :seeded (dev-seed/maybe-seed! {:db db :dev? true})))
    (let [articles (articles-id)
          seeded (jdbc/execute! db
                                ["SELECT target_id FROM relations WHERE owner_id = ?" articles])]
      (is (seq seeded) "the demo articles were seeded at all")
      (doseq [{:relations/keys [target_id]} seeded]
        (let [entry (get-in (ds/get-item db {:id target_id}) [:data :contexts articles])]
          (is (some? entry)
              (str "item " target_id " has a mirror entry for the context it is filed under"))
          (is (true? (:show-badge? entry))
              "and it says what the row says: show_badge 1, so the card wears the badge")
          (is (= "Articles" (:title entry)))
          (is (true? (:is-context? entry))
              "which context-badges also insists on before it draws anything"))))))

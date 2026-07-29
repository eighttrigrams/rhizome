(ns api.replica-test
  "Read-only replica mode over /ui, where queries and mutations share one POST:
   the refusal has to be per command, not per method. A booted instance carries
   its role in `config/config` (decided once at startup, see
   config/read-only-replica?), so redefining that map is what a replica looks
   like from the dispatcher's side."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [api.harness :refer [call!]]
            [api.helpers :refer [with-fresh-db]]
            [config :as config]
            [et.vp.ds :as ds]
            [et.vp.ds.search-test :refer [db]]))

(defmacro ^:private as-replica
  [& body]
  `(with-redefs [config/config {:db db :read-only-replica? true}] ~@body))

(defn- refused? [resp] (boolean (re-find #"read-only replica" (str (:read-only-refused resp)))))

(defn- updated-at-ctx
  [id]
  (:items/updated_at_ctx
    (jdbc/execute-one! db ["select updated_at_ctx from items where id = ?" id])))

(defn- stamp-sentinel!
  "Park a value in updated_at_ctx that no real touch would ever write, so a
   reprioritize is unmistakable."
  [id]
  (jdbc/execute-one! db ["update items set updated_at_ctx = '1999-01-01 00:00:00' where id = ?" id])
  (updated-at-ctx id))

(deftest replica-refuses-ui-mutations-test
  (with-fresh-db "mutating commands are refused, gracefully, and write nothing"
    (let [ctx (ds/new-context db {:title "Books"})
          item (ds/new-item db "Sapiens" "" #{(:id ctx)} 1)]
      (doseq [call [[:insert-context nil {:title "ShouldNotPersist"}]
                    [:insert-item {:selected-item ctx} {:title "ShouldNotPersist"}]
                    [:delete-item {} item]
                    [:delete-context {} ctx]
                    [:reprioritize-item {} item]
                    [:upgrade-item-to-context {:selected-item item}]
                    [:update-annotations {} {:item-id (:id item)
                                             :context-id (:id ctx)
                                             :global-annotation "nope"}]
                    [:store-current-view {:selected-item ctx} ctx]
                    [:cycle-search-mode {:selected-item ctx}]
                    [:edit-item-in-obsidian {} item]
                    [:sync-obsidian-changes {} item]
                    [:add-atom-poll-feed {} "https://example.com/feed.xml"]
                    [:add-youtube-poll-channel {} "https://www.youtube.com/@example" nil]]]
        (is (refused? (as-replica (apply call! call))) (str (first call))))
      (testing "nothing was written"
        (is (nil? (:id (ds/get-item-by-title db {:title "ShouldNotPersist"}))))
        (is (= (:id item) (:id (ds/get-item db {:id (:id item)}))))
        (is (= (:id ctx) (:id (ds/get-item db {:id (:id ctx)}))))
        (is (nil? (:annotation (ds/get-item db {:id (:id item)}))))
        (is (empty? (jdbc/execute! db ["select * from atom_poll_feeds"])))
        (is (empty? (jdbc/execute! db ["select * from youtube_poll_channels"])))))))

(deftest replica-serves-ui-queries-test
  (with-fresh-db "query commands pass through untouched"
    (let [ctx (ds/new-context db {:title "Books"})
          item (ds/new-item db "Sapiens" "" #{(:id ctx)} 1)]
      (testing "search"
        (let [resp (as-replica (call! :list-resources {}))]
          (is (not (refused? resp)))
          (is (contains? resp :items))
          (is (contains? resp :contexts))))
      (testing "opening a context works and does not touch its ordering timestamp"
        (let [sentinel (stamp-sentinel! (:id ctx))
              resp (as-replica (call! :fetch-context {} [{:id (:id ctx)} false]))]
          (is (not (refused? resp)))
          (is (= (:id ctx) (:id (:selected-item resp))))
          (is (= sentinel (updated-at-ctx (:id ctx)))
              "reprioritize is skipped on a replica -- otherwise this read would hit the read-only db")))
      (testing "the other read commands"
        (is (not (refused? (as-replica (call! :fetch-item-description {} {:id (:id item)})))))
        (is (not (refused? (as-replica (call! :fetch-aggregated-contexts {:selected-item ctx})))))
        (is (not (refused? (as-replica (call! :deselect-context {})))))
        (is (not (refused? (as-replica (call! :list-atom-poll-feeds {})))))
        (is (not (refused? (as-replica (call! :list-youtube-poll-channels {})))))))))

(deftest replica-refuses-the-writing-list-resources-cmd-test
  (with-fresh-db "list-resources is a query, except for the :cmd that saves a description"
    (let [ctx (ds/new-context db {:title "Books"})
          resp (as-replica (call! :list-resources
                                  {:cmd :update-context-description
                                   :arg {:id (:id ctx) :description "nope"}}))]
      (is (refused? resp))
      (is (str/blank? (str (:description (ds/get-item db {:id (:id ctx)}))))))))

(deftest unclassified-command-is-refused-test
  (with-fresh-db "the classification fails closed: what is not a known query counts as a write"
    (is (refused? (as-replica (call! :no-such-command))))))

(deftest dev-mode-is-unaffected-test
  (with-fresh-db "dev mode (no marker, no guard): the same mutations go through"
    (is (false? (:read-only-replica? config/config)))
    (let [{ctx :selected-item} (call! :insert-context nil {:title "Books"})
          sentinel (stamp-sentinel! (:id ctx))]
      (is (some? (:id ctx)))
      (call! :fetch-context {} [{:id (:id ctx)} false])
      (is (not= sentinel (updated-at-ctx (:id ctx)))
          "outside replica mode, opening a context still reprioritizes it"))))

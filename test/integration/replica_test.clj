(ns replica-test
  "The replica guards that live in `server`: the polling job and /upload."
  (:require [clojure.test :refer [deftest is testing]]
            [config :as config]
            [poll :as poll]
            [server :as server]))

(def ^:private replica-config {:read-only-replica? true})
(def ^:private primary-config {:read-only-replica? false})

(deftest poll-scheduling-decision-test
  (testing "the pollers write items and seen-rows, so a replica does not schedule them"
    (with-redefs [config/config replica-config]
      (is (false? (server/poll-scheduling-enabled?)))))
  (testing "a primary does"
    (with-redefs [config/config primary-config]
      (is (true? (server/poll-scheduling-enabled?)))))
  (testing "and e2e still does not, replica or not"
    (with-redefs [config/config (assoc primary-config :e2e? true)]
      (is (false? (server/poll-scheduling-enabled?))))))

(deftest replica-never-starts-the-scheduler-test
  (let [started (atom [])
        record! (fn [db] (swap! started conj db))]
    (testing "startup skips the job entirely on a replica -- it is not scheduled and then blocked"
      (with-redefs [config/config replica-config
                    poll/start-scheduler! record!]
        (#'server/start-pollers!)
        (is (empty? @started))))
    (testing "a primary schedules it"
      (with-redefs [config/config (assoc primary-config :db ::db)
                    poll/start-scheduler! record!]
        (#'server/start-pollers!)
        (is (= [::db] @started))))))

(deftest upload-refused-on-a-replica-test
  (testing "/upload is a write, so it gets the graceful refusal"
    (with-redefs [config/config replica-config]
      (let [resp (server/upload-handler {:multipart-params {}})]
        (is (= 403 (:status resp)))
        (is (re-find #"read-only replica" (:body resp)))))))

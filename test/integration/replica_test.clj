(ns replica-test
  "The replica guards that live in `server`: the startup steps, the polling job
   and /upload."
  (:require [clojure.test :refer [deftest is testing]]
            [config :as config]
            [datastore.schema :as schema]
            [dev-seed :as dev-seed]
            [poll :as poll]
            [server :as server]
            [upload :as upload]))

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

(deftest write-side-startup-skipped-on-a-replica-test
  (let [called (atom [])
        record! (fn [k] (fn [& _] (swap! called conj k) nil))]
    (testing "a replica runs neither of them -- not the seed, and not the
              ImageMagick gate, which would refuse it a boot over preview
              downscaling it can never do"
      (with-redefs [config/config replica-config
                    upload/ensure-convert! (record! :ensure-convert)
                    schema/apply-schema! (record! :apply-schema)
                    dev-seed/maybe-seed! (record! :maybe-seed)]
        (#'server/prepare-for-writing!)
        (is (empty? @called))))
    (testing "a primary runs both"
      (with-redefs [config/config (assoc primary-config :db ::db)
                    upload/ensure-convert! (record! :ensure-convert)
                    schema/apply-schema! (record! :apply-schema)
                    dev-seed/maybe-seed! (record! :maybe-seed)]
        (#'server/prepare-for-writing!)
        (is (= [:ensure-convert :maybe-seed] @called))))))

(deftest the-app-server-does-not-apply-the-schema-test
  ;; Since step 4 the db-server owns the file and applies the schema as it opens
  ;; it, before this process is started at all. `apply-schema!` is still redefined
  ;; in the test above, so this says the difference out loud rather than leaving
  ;; it to be read out of an absent keyword in a vector.
  (let [called (atom [])]
    (with-redefs [config/config (assoc primary-config :db ::db)
                  upload/ensure-convert! (constantly nil)
                  schema/apply-schema! (fn [& _] (swap! called conj :apply-schema) nil)
                  dev-seed/maybe-seed! (constantly nil)]
      (#'server/prepare-for-writing!)
      (is (empty? @called)
          "a primary app-server applying the schema would be a second opinion about
           a file it does not open"))))

(deftest upload-refused-on-a-replica-test
  (testing "/upload is a write, so it gets the graceful refusal"
    (with-redefs [config/config replica-config]
      (let [resp (server/upload-handler {:multipart-params {}})]
        (is (= 403 (:status resp)))
        (is (re-find #"read-only replica" (:body resp)))))))

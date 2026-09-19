(ns config-test
  "The marker lookup, and what the app-server is handed as `:db`.

   The rule that read the marker -- prod mode without it meant a read-only
   replica -- retired in step 4 of the architecture rework, along with replicas
   themselves. The marker did not: it elects which machine runs the hub now, and
   `db-server-config-test` is where that meaning is pinned. What is left here is
   the filesystem lookup, which is unchanged."
  (:require [clojure.test :refer [deftest is testing]]
            [config :as config])
  (:import [java.io File]))

(deftest primary-marker-present-test
  (testing "the marker is a plain file lookup in the start directory"
    (let [f (File/createTempFile "primary" ".nosync")]
      (try
        (is (true? (config/primary-marker-present? (.getPath f))))
        (finally (.delete f)))
      (is (false? (config/primary-marker-present? (.getPath f)))))))

(deftest the-marker-name-is-the-one-the-sync-excludes-test
  (is (= "primary.nosync" config/primary-marker)
      "the .nosync suffix is what keeps it off every other machine"))

;; --- what the app-server is handed as `:db`, since the split ----------------
;; The role tests above are the human's and untouched; these are step 4's, and
;; they are here rather than in a file of their own because they are about the
;; same `config/config`.

(deftest test-mode-keeps-a-local-datasource-test
  ;; The subtle one in the whole step, and it survived the retirement of the
  ;; statement protocol unchanged. Everywhere else the `server` holds NO handle
  ;; at all -- since step 4 `:db` is nil outside test mode, because that process
  ;; reaches no database -- but the test database is a shared-cache in-memory
  ;; SQLite, which lives inside ONE JVM. No separate process could open it, and
  ;; 88 statements across 19 test files use `(:db config/config)` directly.
  (testing "this JVM is in test mode"
    (is (true? (:test? config/config))))
  (testing "and its :db is a real DataSource"
    (is (instance? javax.sql.DataSource (:db config/config))))
  (testing "and it has no hub to forward to, which is what makes it answer itself"
    (is (nil? (:hub-url config/config)))))

(deftest db-url-derivation-test
  ;; Still `db-url`, still derived from the `:db-server` section, but what it
  ;; feeds changed: it is `:hub-url` now -- where the hub is -- rather than the
  ;; url inside a remote db handle. The rename of the section itself is step 5.
  (testing "derived from the :db-server section both processes read"
    (is (= "http://127.0.0.1:3141"
           (#'config/db-url {:db-server {:port 3141}}))))
  (testing ":db-url wins -- the separate-files case, and the hook for a db on another machine"
    (is (= "http://elsewhere:3008"
           (#'config/db-url {:db-url "http://elsewhere:3008" :db-server {:port 3141}}))))
  (testing "neither is a refusal, not a guessed port"
    (let [t (try (#'config/db-url {:port 3140 :dev? true}) nil (catch Throwable t t))]
      (is (some? t))
      (is (re-find #":db-server" (.getMessage t)))
      (is (re-find #":db-url" (.getMessage t))))))

(deftest moved-keys-are-refused-by-name-test
  ;; Both would fail silently: an app that no longer opens a file would ignore a
  ;; top-level :db-path, and a :vec-path left under :semsearch would turn the vec
  ;; extension off everywhere -- semantic search quietly wrong, ^:vector tests
  ;; quietly skipped -- with nothing failing anywhere.
  (testing "a config.edn from before the split says so"
    (let [t (try (#'config/check-moved-keys {:db-path "./rhizome.db"}) nil (catch Throwable t t))]
      (is (some? t))
      (is (re-find #":db-path moved into the :db-server section" (.getMessage t))))
    (let [t (try (#'config/check-moved-keys {:semsearch {:vec-path "./x/vec0"}}) nil
                 (catch Throwable t t))]
      (is (some? t))
      (is (re-find #":vec-path moved from :semsearch" (.getMessage t)))))
  (testing "and the keys that stayed are left alone"
    (let [c {:semsearch {:ollama-url "http://127.0.0.1:11434" :ollama-model "m"}
             :db-server {:port 3141 :db-path "./rhizome.db"}}]
      (is (= c (#'config/check-moved-keys c))))))

(deftest a-section-without-a-port-says-so-test
  ;; Not "no :db-server section" -- there is one, and a message that sends the
  ;; reader looking for something already in front of them costs more than a
  ;; missing message. The db-server defaults its own port when the section omits
  ;; one; this process deliberately does not follow it there, because a guess
  ;; that happened to be wrong comes up green and fails on the first statement.
  (let [t (try (#'config/db-url {:db-server {:db-path "./rhizome.db"}}) nil
               (catch Throwable t t))]
    (is (some? t))
    (is (re-find #"the :db-server section has no :port" (.getMessage t)))
    (is (not (re-find #"no :db-server section and no :db-url" (.getMessage t)))
        "the message for an absent section is a different message"))
  (testing "and an absent section still gets that other one"
    (let [t (try (#'config/db-url {:port 3140}) nil (catch Throwable t t))]
      (is (re-find #"no :db-server section and no :db-url" (.getMessage t))))))

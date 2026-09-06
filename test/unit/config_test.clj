(ns config-test
  "The primary/replica role decision. Pure function plus the filesystem lookup
   feeding it, so both halves are testable without a prod-mode process."
  (:require [clojure.test :refer [deftest is testing]]
            [config :as config]
            [db :as db])
  (:import [java.io File]))

(deftest read-only-replica-decision-test
  (testing "prod mode without the marker is the only read-only-replica case"
    (is (true? (config/read-only-replica? {:dev? false} false))
        "prod + no marker -> read-only replica")
    (is (false? (config/read-only-replica? {:dev? false} true))
        "prod + marker -> primary, writes enabled")
    (is (false? (config/read-only-replica? {:dev? true} false))
        "dev needs no marker")
    (is (false? (config/read-only-replica? {:dev? true} true))
        "dev is unaffected by a marker being there")))

(deftest primary-marker-present-test
  (testing "the marker is a plain file lookup in the start directory"
    (let [f (File/createTempFile "primary" ".nosync")]
      (try
        (is (true? (config/primary-marker-present? (.getPath f))))
        (finally (.delete f)))
      (is (false? (config/primary-marker-present? (.getPath f)))))))

(deftest this-process-booted-as-primary-test
  (testing "the test config (dev mode, no marker) carries the decision as :read-only-replica?"
    (is (false? (:read-only-replica? config/config))))
  (testing "the marker name is the one the owner's sync excludes"
    (is (= "primary.nosync" config/primary-marker))))

;; --- what the app-server is handed as `:db`, since the split ----------------
;; The role tests above are the human's and untouched; these are step 4's, and
;; they are here rather than in a file of their own because they are about the
;; same `config/config`.

(deftest test-mode-keeps-a-local-datasource-test
  ;; The subtle one in the whole step. Everywhere else the app's `:db` is a
  ;; remote handle and this process holds no datasource at all -- but the test
  ;; database is a shared-cache in-memory SQLite, which lives inside ONE JVM.
  ;; No separate process could open it, so there is no db-server for this handle
  ;; to point at; the one the integration suites run against is booted inside
  ;; this JVM by `db-harness`. And 88 statements across 19 test files use
  ;; `(:db config/config)` directly, which is the two-names-onto-one-database
  ;; decision from step 3.
  (testing "this JVM is in test mode"
    (is (true? (:test? config/config))))
  (testing "and its :db is the DataSource, not a db-server url"
    (is (instance? javax.sql.DataSource (:db config/config)))
    (is (not (db/remote? (:db config/config))))))

(deftest db-url-derivation-test
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

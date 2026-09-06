(ns db-server-config-test
  "`db-server/config-opts`: the one place the inner server reads a file.

   What it has to get right is narrow and load-bearing. It reads the
   `:db-server` section and nothing else of its own -- which is what makes the
   shared config.edn and a standalone `{:db-server {…}}` the same file format,
   read by the same reader -- plus the top-level `:dev?`, because the
   primary/replica rule needs it and the two processes have to reach the same
   verdict about the same directory."
  (:require [clojure.test :refer [deftest is testing]]
            [db-server]
            [role :as role])
  (:import [java.io File]))

(defn- config-file
  "A config.edn holding `content`, somewhere a test can point a reader at."
  ^File [content]
  (let [f (File/createTempFile "config" ".edn")]
    (.deleteOnExit f)
    (spit f content)
    f))

(defn- opts-for [content]
  (db-server/config-opts (.getPath (config-file content))))

(deftest reads-the-section-and-nothing-else-of-its-own-test
  (testing "the shared file: the app's keys are there and none of them arrives"
    (let [opts (opts-for (str "{:port 3140 :dev? true"
                              " :semsearch {:ollama-url \"http://127.0.0.1:11434\"}"
                              " :folders {:images \"./files/x\"}"
                              " :db-server {:port 3141 :db-path \"./rhizome.db\""
                              "             :vec-path \"./.sqlite-vec/vec0\"}}"))]
      (is (= {:port 3141 :db-path "./rhizome.db" :vec-path "./.sqlite-vec/vec0"
              :read-only? false}
             opts)
          "the app's :port is 3140 and the db-server's is 3141: it took its own")))
  (testing "the standalone file: same reader, nothing else required"
    ;; The marker is redefined for the same reason its siblings below do it: the
    ;; real lookup is a file in the checkout, so without this a developer who
    ;; drops ./primary.nosync in to exercise prod behaviour reddens a test that
    ;; is about which keys are read.
    (with-redefs [role/primary-marker-present? (constantly false)]
      (is (= {:port 3008 :db-path "/db/rhizome.db.nosync" :vec-path nil :read-only? true}
             (opts-for "{:db-server {:port 3008 :db-path \"/db/rhizome.db.nosync\"}}"))
          "no :dev? in the file means prod, and prod with no marker is read-only"))))

(deftest the-role-comes-from-the-marker-and-the-mode-test
  ;; The same rule the app-server reaches, from the same directory -- `role` owns
  ;; it precisely so the two cannot drift.
  (testing "prod without the marker is read-only: the structural ban"
    (with-redefs [role/primary-marker-present? (constantly false)]
      (is (true? (:read-only? (opts-for "{:db-server {:db-path \"./x.db\"}}"))))))
  (testing "prod with the marker is the primary"
    (with-redefs [role/primary-marker-present? (constantly true)]
      (is (false? (:read-only? (opts-for "{:db-server {:db-path \"./x.db\"}}"))))))
  (testing "dev needs no marker, which is why :dev? is read at all"
    (with-redefs [role/primary-marker-present? (constantly false)]
      (is (false? (:read-only? (opts-for "{:dev? true :db-server {:db-path \"./x.db\"}}")))))))

(deftest the-port-defaults-to-the-one-the-scripts-fall-back-to-test
  (is (= 3141 (:port (opts-for "{:db-server {:db-path \"./x.db\"}}"))))
  (is (= db-server/default-port 3141)
      "scripts/detect-ports.sh falls back to the same number, on purpose"))

(deftest moved-keys-are-refused-by-name-test
  ;; Both are silent failures if merely ignored: a top-level :db-path leaves this
  ;; server pointed at nothing, and a :vec-path still under :semsearch turns the
  ;; vec extension off with nothing to see anywhere.
  (testing "a top-level :db-path from before the split"
    (let [t (try (opts-for "{:db-path \"./rhizome.db\" :db-server {:db-path \"./x.db\"}}")
                 nil (catch Throwable t t))]
      (is (some? t))
      (is (re-find #":db-path moved into the :db-server section" (.getMessage t)))))
  (testing ":vec-path still under :semsearch"
    (let [t (try (opts-for (str "{:semsearch {:vec-path \"./v/vec0\"}"
                                " :db-server {:db-path \"./x.db\"}}"))
                 nil (catch Throwable t t))]
      (is (some? t))
      (is (re-find #":vec-path moved from :semsearch" (.getMessage t)))))
  (testing "and :semsearch keeping only the embedder's keys is fine"
    (is (map? (opts-for (str "{:semsearch {:ollama-url \"u\" :ollama-model \"m\"}"
                             " :db-server {:db-path \"./x.db\"}}"))))))

(deftest a-file-with-no-section-is-refused-test
  (let [t (try (opts-for "{:port 3140 :dev? true}") nil (catch Throwable t t))]
    (is (some? t))
    (is (re-find #"no :db-server section" (.getMessage t)))
    (is (re-find #"make onboard" (.getMessage t))
        "the message says how to get one rather than only that there is none")))

;; --- the e2e alias must not be able to open the developer's database --------
;; Before the split, `-Drhizome.e2e=1` picked the file, so an e2e JVM physically
;; could not reach ./rhizome.db. Now the file is the db-server's :db-path and
;; scripts/e2e.sh points it here by exporting DB_PATH -- which is a thing that
;; can be forgotten. e2e's globalSetup POSTs /test/reset, and that deletes every
;; row in whatever database is behind it, so the old guarantee is kept by
;; refusal instead.

(defn- as-e2e-jvm
  "Run `f` with the sysprop the `:e2e` alias sets, and put it back after."
  [f]
  (let [before (System/getProperty "rhizome.e2e")]
    (try (System/setProperty "rhizome.e2e" "1")
         (f)
         (finally (if before
                    (System/setProperty "rhizome.e2e" before)
                    (System/clearProperty "rhizome.e2e"))))))

(deftest an-e2e-db-server-refuses-any-other-database-test
  (with-redefs [role/primary-marker-present? (constantly true)]
    (testing "the dev database, which is the mistake that costs something"
      (let [t (as-e2e-jvm
                #(try (opts-for "{:dev? true :db-server {:db-path \"./rhizome.db\"}}")
                      nil (catch Throwable t t)))]
        (is (some? t))
        (is (re-find #"refusing to open .* under -Drhizome\.e2e=1" (.getMessage t)))
        (is (re-find #"/test/reset" (.getMessage t))
            "and says what would have happened, not just that it refused")))
    (testing "the e2e database is accepted, spelled either way"
      (doseq [p ["./test/rhizome-e2e.db" "test/rhizome-e2e.db"]]
        (is (= p (:db-path (as-e2e-jvm
                             #(opts-for (str "{:dev? true :db-server {:db-path \"" p "\"}}")))))
            (str "canonical paths, so " p " is the same answer"))))
    (testing "and outside an e2e JVM the db-path is nobody's business but the config's"
      (is (= "./rhizome.db"
             (:db-path (opts-for "{:dev? true :db-server {:db-path \"./rhizome.db\"}}")))))))

(deftest a-missing-db-path-is-start-s-refusal-not-an-npe-test
  ;; `check-e2e-db-path!` ran before anything established there was a :db-path
  ;; at all, so a section without one met getCanonicalPath with nil and the
  ;; clean ":db-path is required" from `start!` never got the chance. Reachable
  ;; with a hand-edited config plus the :e2e alias, which is exactly the
  ;; combination someone debugging an e2e run is in.
  (with-redefs [role/primary-marker-present? (constantly true)]
    (let [opts (as-e2e-jvm #(opts-for "{:dev? true :db-server {:port 3199}}"))]
      (is (nil? (:db-path opts))
          "the reader gets out of the way and lets start! say it"))
    (let [t (as-e2e-jvm
              #(try (db-server/start! (opts-for "{:dev? true :db-server {:port 0}}"))
                    nil (catch Throwable t t)))]
      (is (some? t))
      (is (re-find #":db-path is required" (.getMessage t))))))

(ns et.rz.hub.main-config-test
  "`et.rz.hub.main/config-opts`: the one place the hub reads a file.

   What it has to get right is narrow and load-bearing. It reads the
   `:hub` section and nothing else of its own -- which is what makes the
   shared config.edn and a standalone `{:hub {…}}` the same file format,
   read by the same reader -- plus the top-level `:dev?`, because the
   primary/replica rule needs it and the two processes have to reach the same
   verdict about the same directory."
  (:require [clojure.test :refer [deftest is testing]]
            [et.rz.hub.main :as hub-main]
            [et.rz.role :as role])
  (:import [java.io File]))

(defn- config-file
  "A config.edn holding `content`, somewhere a test can point a reader at."
  ^File [content]
  (let [f (File/createTempFile "config" ".edn")]
    (.deleteOnExit f)
    (spit f content)
    f))

(defn- opts-for [content]
  (hub-main/config-opts (.getPath (config-file content))))

(defn- with-config
  "Call `f` with the path of a config.edn holding `content`."
  [content f]
  (f (.getPath (config-file content))))

(deftest reads-the-section-and-nothing-else-of-its-own-test
  (testing "the shared file: the app's keys are there and none of them arrives"
    (let [opts (opts-for (str "{:port 3140 :dev? true"
                              " :semsearch {:ollama-url \"http://127.0.0.1:11434\"}"
                              " :folders {:images \"./files/x\"}"
                              " :hub {:port 3141 :db-path \"./rhizome.db\""
                              "             :vec-path \"./.sqlite-vec/vec0\"}}"))]
      (is (= {:port 3141 :db-path "./rhizome.db" :vec-path "./.sqlite-vec/vec0"
              :allow-reset? true}
             opts)
          "the server's :port is 3140 and the hub's is 3141: it took its own")))
  (testing "the standalone file: same reader, nothing else required"
    ;; The marker is redefined for the same reason its siblings below do it: the
    ;; real lookup is a file in the checkout, so without this a developer who
    ;; drops ./primary.nosync in to exercise prod behaviour reddens a test that
    ;; is about which keys are read.
    (with-redefs [role/primary-marker-present? (constantly false)]
      (is (= {:port 3008 :db-path "/db/rhizome.db.nosync" :vec-path nil
              :allow-reset? false}
             (opts-for "{:hub {:port 3008 :db-path \"/db/rhizome.db.nosync\"}}"))
          (str "no :dev? in the file means prod, and a prod hub refuses "
               "/test/reset. There is no :read-only? here at all any more: the "
               "marker elects the hub, it does not demote it")))))

(deftest the-marker-elects-the-hub-rather-than-demoting-it-test
  ;; The marker's meaning changed in step 4 and this is where it is pinned.
  ;; Before: present meant "may write", absent meant a read-only replica. Now
  ;; there are no replicas -- one hub, one mode -- so it answers a different
  ;; question: WHICH MACHINE runs the hub. A hub that boots is writable; one
  ;; that was not elected does not boot.
  ;;
  ;; Getting this wrong is worse than two writers on one file. The database is
  ;; `rhizome.db.nosync`, and `.nosync` is exactly the suffix that keeps iCloud
  ;; from syncing it -- so two hubs are two DATABASES diverging in silence.
  (testing "no config-opts key carries a role any more"
    (with-redefs [role/primary-marker-present? (constantly false)]
      (is (not (contains? (opts-for "{:hub {:db-path \"./x.db\"}}") :read-only?)))))
  (testing "prod without the marker refuses to boot, and says what to do"
    (with-redefs [role/primary-marker-present? (constantly false)]
      (let [t (try (with-config "{:hub {:db-path \"./x.db\"}}"
                     #(hub-main/check-elected! %))
                   nil
                   (catch Throwable t t))]
        (is (some? t))
        (is (re-find #"was not elected" (.getMessage t)))
        (is (re-find #"stop the hub on the old one" (.getMessage t))
            "the remedy names the order, because doing it the other way round
             leaves two hubs running"))))
  (testing "prod with the marker boots"
    (with-redefs [role/primary-marker-present? (constantly true)]
      (is (nil? (with-config "{:hub {:db-path \"./x.db\"}}"
                  #(hub-main/check-elected! %))))))
  (testing "dev needs no marker, which is why :dev? is read at all"
    (with-redefs [role/primary-marker-present? (constantly false)]
      (is (nil? (with-config "{:dev? true :hub {:db-path \"./x.db\"}}"
                  #(hub-main/check-elected! %)))))))

(deftest the-port-defaults-to-the-one-the-scripts-fall-back-to-test
  (is (= 3141 (:port (opts-for "{:hub {:db-path \"./x.db\"}}"))))
  (is (= hub-main/default-port 3141)
      "scripts/detect-ports.sh falls back to the same number, on purpose"))

(deftest moved-keys-are-refused-by-name-test
  ;; All three are silent failures if merely ignored: a top-level :db-path
  ;; leaves this process pointed at nothing, a :vec-path still under :semsearch
  ;; turns the vec extension off with nothing to see anywhere, and a section
  ;; still called :db-server hides :port, :db-path and :vec-path together.
  (testing "a top-level :db-path from before the split"
    (let [t (try (opts-for "{:db-path \"./rhizome.db\" :hub {:db-path \"./x.db\"}}")
                 nil (catch Throwable t t))]
      (is (some? t))
      (is (re-find #":db-path moved into the :hub section" (.getMessage t)))))
  (testing ":vec-path still under :semsearch"
    (let [t (try (opts-for (str "{:semsearch {:vec-path \"./v/vec0\"}"
                                " :hub {:db-path \"./x.db\"}}"))
                 nil (catch Throwable t t))]
      (is (some? t))
      (is (re-find #":vec-path moved from :semsearch" (.getMessage t)))))
  ;; The cutover guard, and the hub's half of it -- `et.rz.config` refuses the
  ;; same key for the `server`. Without it a deployed config.edn that still
  ;; said :db-server would give this process no :port, no :db-path and no
  ;; :vec-path, so it would come up on its default port against a database
  ;; nobody named -- and, the db being `.nosync` and therefore unsynced,
  ;; quietly create a second one rather than fail.
  (testing "a section still called :db-server, which is the cutover edit"
    (let [t (try (opts-for "{:db-server {:port 3008 :db-path \"./rhizome.db.nosync\"}}")
                 nil (catch Throwable t t))]
      (is (some? t))
      (is (re-find #":db-server section is now :hub" (.getMessage t)))
      (is (re-find #"silently ignored" (.getMessage t))
          "it says what would happen if it were tolerated, not just that it is refused")))
  (testing "and :semsearch keeping only the embedder's keys is fine"
    (is (map? (opts-for (str "{:semsearch {:ollama-url \"u\" :ollama-model \"m\"}"
                             " :hub {:db-path \"./x.db\"}}"))))))

(deftest a-file-with-no-section-is-refused-test
  (let [t (try (opts-for "{:port 3140 :dev? true}") nil (catch Throwable t t))]
    (is (some? t))
    (is (re-find #"no :hub section" (.getMessage t)))
    (is (re-find #"make onboard" (.getMessage t))
        "the message says how to get one rather than only that there is none")))

;; --- the e2e alias must not be able to open the developer's database --------
;; Before the split, `-Drhizome.e2e=1` picked the file, so an e2e JVM physically
;; could not reach ./rhizome.db. Now the file is the hub's :db-path and
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

(deftest an-e2e-hub-refuses-any-other-database-test
  (with-redefs [role/primary-marker-present? (constantly true)]
    (testing "the dev database, which is the mistake that costs something"
      (let [t (as-e2e-jvm
                #(try (opts-for "{:dev? true :hub {:db-path \"./rhizome.db\"}}")
                      nil (catch Throwable t t)))]
        (is (some? t))
        (is (re-find #"refusing to open .* under -Drhizome\.e2e=1" (.getMessage t)))
        (is (re-find #"/test/reset" (.getMessage t))
            "and says what would have happened, not just that it refused")))
    (testing "the e2e database is accepted, spelled either way"
      (doseq [p ["./test/rhizome-e2e.db" "test/rhizome-e2e.db"]]
        (is (= p (:db-path (as-e2e-jvm
                             #(opts-for (str "{:dev? true :hub {:db-path \"" p "\"}}")))))
            (str "canonical paths, so " p " is the same answer"))))
    (testing "and outside an e2e JVM the db-path is nobody's business but the config's"
      (is (= "./rhizome.db"
             (:db-path (opts-for "{:dev? true :hub {:db-path \"./rhizome.db\"}}")))))))

(deftest a-missing-db-path-is-start-s-refusal-not-an-npe-test
  ;; `check-e2e-db-path!` ran before anything established there was a :db-path
  ;; at all, so a section without one met getCanonicalPath with nil and the
  ;; clean ":db-path is required" from `start!` never got the chance. Reachable
  ;; with a hand-edited config plus the :e2e alias, which is exactly the
  ;; combination someone debugging an e2e run is in.
  (with-redefs [role/primary-marker-present? (constantly true)]
    (let [opts (as-e2e-jvm #(opts-for "{:dev? true :hub {:port 3199}}"))]
      (is (nil? (:db-path opts))
          "the reader gets out of the way and lets start! say it"))
    (let [t (as-e2e-jvm
              #(try (hub-main/start! (opts-for "{:dev? true :hub {:port 0}}"))
                    nil (catch Throwable t t)))]
      (is (some? t))
      (is (re-find #":db-path is required" (.getMessage t))))))

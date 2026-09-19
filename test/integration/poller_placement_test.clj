(ns poller-placement-test
  "The feed pollers run exactly once (arch rework 2, step 3).

   They used to be `server`'s, scheduled at startup on whatever handle the
   process held. With one hub and a `server` on every machine that is wrong in
   the expensive direction: two or three machines would each read every youtube
   channel and atom feed, and race to insert the same items. So they moved to
   the hub, and `server` schedules them only when there is no hub to do it.

   That leaves two predicates, in two namespaces, that have to stay
   complementary. This pins them to each other rather than to their own
   docstrings:

   - **never both.** Two schedulers is the duplicate polling the move was made
     to prevent.
   - **never neither**, in the one world where feeds are meant to be read.
     Nothing would fail if the pollers silently stopped running -- rhizome
     would simply go quiet, and quietly, which is the worst way for this to
     break."
  (:require [clojure.test :refer [deftest is testing]]
            [et.rz.config :as config]
            [et.rz.hub.main :as db-server]
            [et.rz.server.main :as server]))

(def ^:private server-polls? #'server/poll-scheduling-enabled?)

(defn- verdicts
  "What each half answers in one named world: `[server? hub?]`.

   `hub` is the server map `db-server/start!` returns, of which only
   `:read-only?` is consulted -- so it is given as a map rather than booted,
   because booting one to ask it a question about scheduling would be a network
   call and a file."
  [{:keys [config-overrides hub e2e?]}]
  (let [prop (System/getProperty "rhizome.e2e")]
    (try
      (if e2e?
        (System/setProperty "rhizome.e2e" "1")
        (System/clearProperty "rhizome.e2e"))
      (with-redefs [config/config (merge config/config config-overrides)]
        [(boolean (server-polls?))
         (boolean (and hub (db-server/poll-scheduling-enabled? hub)))])
      (finally
        (if prop
          (System/setProperty "rhizome.e2e" prop)
          (System/clearProperty "rhizome.e2e"))))))

;; The worlds, by name. `:hub-url` is what decides whether a hub exists at all:
;; set means there is one, absent means this process is holding the file itself.
(def ^:private a-hub
  "A `:hub-url`. Its presence is the whole of \"there is a hub\" (see
   `hub-proxy/hub-url`); nothing here connects to it."
  "http://127.0.0.1:3008")

(def ^:private worlds
  {"prod, a writable hub"
   {:config-overrides {:hub-url a-hub :e2e? false}
    :hub              {:read-only? false}}

   "one process, no hub"
   {:config-overrides {:hub-url nil :e2e? false}
    :hub              nil}

   "e2e"
   {:config-overrides {:hub-url a-hub :e2e? true}
    :hub              {:read-only? false}
    :e2e?             true}

   "a read-only hub"
   {:config-overrides {:hub-url a-hub :e2e? false}
    :hub              {:read-only? true}}})

(deftest never-two-schedulers-test
  (doseq [[name world] worlds]
    (testing (str "in: " name)
      (let [[server? hub?] (verdicts world)]
        (is (not (and server? hub?))
            (str "both halves would schedule the pollers in the world \"" name
                 "\", which is every feed read twice and two writers racing to "
                 "insert the same items"))))))

(deftest the-hub-is-the-one-that-polls-test
  (testing "prod: the hub schedules, the server stands down"
    (is (= [false true] (verdicts (get worlds "prod, a writable hub")))))
  (testing "one process: it does it itself, as it always did"
    (is (= [true false] (verdicts (get worlds "one process, no hub"))))))

(deftest the-worlds-that-must-not-poll-test
  (testing "e2e polls from neither half: a scheduler reaching youtube 30s into a run would make the suite flaky"
    (is (= [false false] (verdicts (get worlds "e2e")))))
  (testing "a read-only hub polls from neither half: it does not own the file, so a tick could only fail"
    (is (= [false false] (verdicts (get worlds "a read-only hub"))))))

(ns et.rz.server.hub-identity-test
  "Which hub answered, and whether it is the one this machine should be talking
   to (`server/hub-identity-problem`).

   `:hub-url` is `http://127.0.0.1:3008` on **every** machine -- the property
   the ssh tunnel buys, and the reason one config.edn is correct everywhere. The
   cost is that \"the hub\" and \"a hub left running from a trip\" are the same
   address, and `/health` used to answer them identically. This is the rule that
   tells them apart, and it is pure, so it can be asked about every world rather
   than only the one a running process happens to be in.

   The rule needs no new configuration: `primary.nosync` already says which
   machine is meant to hold the database, so the expectation is derived rather
   than declared. Present, and the hub must be here; absent, and it must not be.

   Read the two unknown-hostname worlds at the bottom first if you are changing
   anything: they are the ones where a plausible simplification is wrong."
  (:require [clojure.test :refer [deftest is testing]]
            [et.rz.server.main :as server]))

(defn- problem
  [world]
  (:problem (server/hub-identity-problem (assoc world :url "http://127.0.0.1:3008"))))

(def ^:private worlds
  "Every world, named, with the verdict each must reach. nil means \"this is
   fine, boot\"."
  {"the mini: elected, and the hub answering is its own"
   [{:elected? true :mine "mini" :theirs "mini"} nil]

   "the laptop: not elected, and the hub answers through the tunnel"
   [{:elected? false :mine "laptop" :theirs "mini"} nil]

   ;; Finding 3, exactly. The laptop travelled as the hub, came home, lost its
   ;; marker but not its hub, and that hub is now answering on the address the
   ;; tunnel should be on. Booting here reads and writes the stale travel
   ;; database, showing a complete and plausible rhizome with the mini's recent
   ;; work missing.
   "the laptop with a hub left over from a trip"
   [{:elected? false :mine "laptop" :theirs "laptop"} :not-elected-but-hub-is-here]

   ;; The mirror, which comes free and which nobody had named: something else
   ;; holds local :3008 in front of the hub that belongs here -- a stale tunnel
   ;; is the likeliest -- so the mini would serve someone else's database.
   "the mini, but :3008 leads somewhere else"
   [{:elected? true :mine "mini" :theirs "laptop"} :elected-but-hub-is-elsewhere]

   "this machine cannot name itself"
   [{:elected? false :mine nil :theirs "mini"} :own-hostname-unknown]

   ;; A hub older than this check answers /health without a hostname. Passing
   ;; it would mean the check quietly does nothing against exactly the
   ;; deployment most likely to be stale.
   "the hub does not say where it is"
   [{:elected? false :mine "laptop" :theirs nil} :hub-hostname-unknown]

   "neither side can name itself"
   [{:elected? false :mine nil :theirs nil} :own-hostname-unknown]

   "blank is not a name either"
   [{:elected? false :mine "  " :theirs "  "} :own-hostname-unknown]})

(deftest every-world-reaches-its-verdict-test
  (doseq [[name' [world expected]] worlds]
    (testing name'
      (is (= expected (problem world))
          (str "world " (pr-str world) " should be " (pr-str expected))))))

(deftest an-unknown-hostname-never-reads-as-a-match-test
  ;; The one simplification that looks right and is not. nil = nil and "" = ""
  ;; are true, so a rule written as a plain equality would let two machines that
  ;; cannot name themselves agree that they are the same machine -- and the
  ;; no-marker row would then pass exactly where it is needed. Both unknown
  ;; worlds must be refusals, not passes.
  (doseq [[mine theirs] [[nil nil] ["" ""] ["  " "  "] [nil "mini"] ["laptop" nil]]]
    (is (some? (problem {:elected? false :mine mine :theirs theirs}))
        (str "an unknown hostname passed the check: "
             (pr-str [mine theirs])))
    (is (some? (problem {:elected? true :mine mine :theirs theirs}))
        (str "an unknown hostname passed the check with the marker present: "
             (pr-str [mine theirs])))))

(deftest every-refusal-says-what-to-do-test
  ;; This message is read once, by a human, at the moment rhizome will not
  ;; start. It has to name the machine, the address and the way out.
  (doseq [[name' [world expected]] worlds :when expected]
    (testing name'
      (let [m (:message (server/hub-identity-problem
                          (assoc world :url "http://127.0.0.1:3008")))]
        (is (re-find #"127\.0\.0\.1:3008" m) "the message does not name the address")
        (is (< 80 (count m)) "the message is too short to explain anything")))))

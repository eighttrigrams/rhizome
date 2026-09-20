(ns et.rz.server.hub-identity-sweep-test
  "Pins the two guards against a stale local hub to each other.

   There are two, and they overlap on purpose:

   - **In the shell**, `rhizome-start` in the README refuses, on a machine with
     no `primary.nosync`, when `:3008` is held by a process that is not `ssh`.
     It asks the operating system what is listening, so it needs no running hub
     and no `/health`, and it fires **before any process starts**.
   - **In the code**, `et.rz.server.main/hub-identity-problem` refuses when the
     hub that answered `/health` is on the wrong machine. It covers a `server`
     started **any other way** -- by hand, from a make target, by a supervisor,
     or on the mini, where `rhizome-start` is not what is running.

   Neither subsumes the other, and to anyone reading them cold they will look
   redundant. **That is what this test exists for.** Deleting one because the
   other exists reopens the half it did not cover, and the half it did not cover
   is not visible from the line being deleted. So each guard's comment names the
   other, and this fails if either the guard or the naming goes.

   Same job as `poller-placement-test`, which pins the two poller predicates
   against each other across four named worlds rather than against their own
   docstrings. A comment saying \"see the other one\" rots the moment the other
   one is gone; a test does not.

   The README is read from the working directory rather than the classpath,
   because it is not on it and is not in the jar. `make test` runs from the repo
   root, which is the only place these guards can be checked from at all."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [et.rz.server.main :as server]))

(defn- readme []
  (let [f (io/file "README.md")]
    (assert (.isFile f)
            (str "README.md not found in " (System/getProperty "user.dir")
                 " -- run the tests from the repo root"))
    (slurp f)))

(defn- shell-function
  "The body of one of the README's shell functions, as text."
  [name']
  (let [src   (readme)
        start (str/index-of src (str name' "() {"))]
    (is (some? start) (str "the README no longer defines " name' "()"))
    (when start
      ;; To the closing brace at the start of a line -- these are written
      ;; flush-left inside one ```bash block.
      (let [rest' (subs src start)
            end   (str/index-of rest' "\n}")]
        (subs rest' 0 (+ end 2))))))

(deftest the-shell-guard-is-still-there-test
  (let [f (shell-function "rhizome-start")]
    (testing "rhizome-start still refuses a non-ssh holder of :3008"
      (is (re-find #"lsof[^\n]*iTCP:3008[^\n]*-F c" f)
          (str "the lsof guard is gone from rhizome-start. It is the only check "
               "that fires before any process starts, and the only one that does "
               "not have to ask the suspect process about itself."))
      (is (re-find #"!=\s*\"ssh\"" f)
          "the ssh discriminator is gone -- a leftover hub answers /health exactly
           as well as the tunnel does, and only lsof can tell them apart"))
    (testing "and it says why it is not the only line of defence"
      (is (str/includes? f "hub-identity-problem")
          (str "rhizome-start's comment no longer names the code-side guard. "
               "The two look redundant; the comment is what stops the next "
               "reader deleting this one as a duplicate.")))))

(deftest the-stop-function-is-still-ungated-test
  (let [f (shell-function "rhizome-stop")]
    (testing "rhizome-stop kills :3008 whether or not the marker is here"
      (is (re-find #"iTCP:3008" f) "rhizome-stop no longer touches :3008 at all")
      (is (not (re-find #"(?s)\[ -e[^\n]*primary\.nosync[^\n]*\].*iTCP:3008" f))
          (str "the :3008 kill is gated on primary.nosync again. That gate failed "
               "exactly where it was needed: remove the marker before stopping the "
               "hub -- the natural order, and step 3 of 'Moving the hub' -- and the "
               "one function that could kill that hub refuses to. launchd replaces "
               "a killed tunnel in seconds; an orphan hub serves a stale database "
               "forever.")))
    (testing "and it still distinguishes the tunnel from a hub"
      ;; What it *says* about each, and what it says when it could not kill at
      ;; all, is run rather than read -- see `run-rhizome-stop` below.
      (is (str/includes? f "ssh") "rhizome-stop no longer distinguishes the tunnel from a hub"))))

(def ^:private cross-reference-heading
  "How `hub-identity-problem`'s docstring labels the section about the other
   guard. Held here rather than inlined so that renaming the heading is a
   deliberate two-place edit and not something that quietly unpins the check."
  "not the only line of defence")

(defn- cross-reference-section
  "The part of a docstring from `cross-reference-heading` to its end, or nil.

   The point is to ask about the cross-reference *specifically*, rather than
   about the docstring as a whole -- a name that appears somewhere in a long
   docstring proves nothing about the paragraph that was supposed to carry it."
  [doc]
  (when-let [i (str/index-of (str doc) cross-reference-heading)]
    (subs (str doc) i)))

(deftest the-code-guard-is-still-there-and-names-the-shell-one-test
  (let [doc (:doc (meta #'server/hub-identity-problem))]
    (testing "both directions of the rule are live, not just the one in the report"
      (is (= :not-elected-but-hub-is-here
             (:problem (server/hub-identity-problem
                         {:elected? false :mine "a" :theirs "a" :url "u"}))))
      (is (= :elected-but-hub-is-elsewhere
             (:problem (server/hub-identity-problem
                         {:elected? true :mine "a" :theirs "b" :url "u"})))))
    (testing "and its docstring still carries the section that names the shell guard"
      ;; Not `(includes? doc "rhizome-start")`, and the difference is the whole
      ;; value of this assertion. The docstring names `rhizome-start` three
      ;; times: twice in the section that exists to cross-reference it, and
      ;; once, incidentally, in the narrative of the failure ("rhizome-start
      ;; skips starting a hub (no marker)"). A plain substring check is
      ;; satisfied by that incidental mention alone -- so the entire
      ;; cross-reference section could be deleted, which is exactly the thing
      ;; this test exists to prevent, and the test stayed green. Verified by
      ;; deleting it: 0 failures. So the section is located first, and the
      ;; naming is asserted *inside* it.
      (let [section (cross-reference-section doc)]
        (is (some? section)
            (str "hub-identity-problem's docstring no longer has a section saying "
                 "this is not the only line of defence. Reword it as you like, but "
                 "keep a heading this can find -- if you renamed it, rename "
                 "`cross-reference-heading` here with it, deliberately."))
        (is (str/includes? (str section) "rhizome-start")
            (str "hub-identity-problem no longer names the shell guard. Same reason "
                 "as the other direction: the two look redundant, and only the "
                 "comment says what each catches that the other does not."))))))

;; ---------------------------------------------------------------------------
;; Running the stop function, rather than reading it
;;
;; A grep for the word `ssh` cannot tell "killed the tunnel" from "stopped the
;; hub" from "could not kill it at all" -- and that last one is the branch that
;; matters, because a stop function reporting success over a hub that is still
;; running is finding 3 wearing a reassuring sentence. So the function is
;; extracted from the README and **executed**, against a stubbed `lsof` and a
;; `kill` that can be told to fail.
;;
;; **What this does not cover, deliberately:**
;;
;; - The stubs answer the two `lsof` invocations by shape, so this proves the
;;   branch structure and the messages, **not** that the real `lsof` flags are
;;   right on macOS and not that `kill` reaches anything. Those are properties
;;   of the machine the function runs on, and no unit test has them.
;; - **`rhizome-start` is read, never run.** It launches a JVM and then
;;   Tracker.app, and there is no honest way to execute that here. Its `lsof`
;;   guard stays pinned as text, one regex, in the test above -- a weaker pin
;;   than this one, and that asymmetry is the reason it is written down here
;;   rather than left for a reader to notice.
;; - Nothing here pins the *wording* of the messages, only which of the three
;;   was reached. Reword them freely; do not merge them.

(def ^:private lsof-and-kill-stubs
  "Stands in for the two commands `rhizome-stop` shells out to.

   `lsof` answers by the shape of the call, because the function asks it two
   different questions about `:3008` -- `-F c` for the holder's command name
   and `-t` for the pids -- and the `-F c` case must be matched first or the
   looser pattern swallows it. `kill` returns whatever the scenario says: a
   real kill fails when the pid is gone between the lsof and the kill, or when
   it is not this user's to signal, and that is the case worth testing."
  "lsof() {
     case \"$*\" in
       *-iTCP:3007*) printf '%s\\n' \"$P3007\" ;;
       *-iTCP:3008*-F\\ c*) [ -n \"$P3008\" ] && printf 'c%s\\n' \"$HOLDER\" ;;
       *-iTCP:3008*) printf '%s\\n' \"$P3008\" ;;
     esac
     return 0
   }
   kill() { return \"$KILL_RC\"; }\n")

(defn- run-rhizome-stop
  "Run the README's `rhizome-stop` in a world, and return `{:out :exit}`.

   `:pids` is what holds `:3008` (blank for nothing), `:holder` the command
   name `lsof -F c` reports for it, `:kill-rc` what `kill` returns. `:3007` is
   always empty here: that half of the function is not what is under test and
   it is the half nobody changed."
  [{:keys [pids holder kill-rc]}]
  (let [script (str "P3007=''\n"
                    "P3008='" pids "'\n"
                    "HOLDER='" holder "'\n"
                    "KILL_RC=" kill-rc "\n"
                    lsof-and-kill-stubs
                    (shell-function "rhizome-stop") "\n"
                    "rhizome-stop\n")
        {:keys [out exit]} (shell/sh "bash" "-c" script)]
    {:out (str out) :exit exit}))

(deftest the-stop-function-reports-what-actually-happened-test
  (testing "nothing there"
    (let [{:keys [out exit]} (run-rhizome-stop {:pids "" :holder "" :kill-rc 0})]
      (is (str/includes? out "nothing on :3008"))
      (is (zero? exit))))

  (testing "the ssh tunnel, which launchd will put back"
    (let [{:keys [out exit]} (run-rhizome-stop {:pids "111" :holder "ssh" :kill-rc 0})]
      (is (str/includes? out "tunnel")
          "killing the tunnel is not reported as killing the tunnel")
      (is (not (str/includes? out "stopped the hub"))
          "the ssh tunnel was reported as the hub")
      (is (zero? exit))))

  (testing "a hub, which is the thing this ungating exists to kill"
    (let [{:keys [out exit]} (run-rhizome-stop {:pids "111" :holder "java" :kill-rc 0})]
      (is (str/includes? out "stopped the hub"))
      (is (zero? exit))))

  (testing "a kill that failed says so, and does not claim the hub is stopped"
    ;; The branch this test was written for. Written as
    ;; `elif kill $pids && [ \"$holder\" = \"ssh\" ]`, a failed kill falls
    ;; through to the else and prints "stopped the hub on :3008" over a hub
    ;; that is still running and still answering :3008 out of a stale
    ;; database. Which is finding 3 again, from the one function whose whole
    ;; purpose in this change is to prevent it.
    (let [{:keys [out exit]} (run-rhizome-stop {:pids "111" :holder "java" :kill-rc 1})]
      (is (not (str/includes? out "stopped the hub"))
          (str "a kill that failed was reported as a hub that stopped. This is "
               "the `kill $pids && [ ... ]` shape coming back: the && sends a "
               "failed kill down the else branch, which is the success message."))
      (is (re-find #"(?i)could not kill" out)
          "a failed kill said nothing about having failed")
      (is (not (zero? exit))
          "a failed kill returned success, so nothing calling this can tell"))))

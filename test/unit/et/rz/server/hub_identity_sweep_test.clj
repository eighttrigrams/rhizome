(ns et.rz.server.hub-identity-sweep-test
  "Pins the two guards against a stale local hub to each other.

   There are two, and they overlap on purpose:

   - **In the shell**, on a machine with no `primary.nosync`, a start script
     refuses when `:3008` is held by a process that is not `ssh`. It asks the
     operating system what is listening, so it needs no running hub and no
     `/health`, and it fires **before any process starts**.
   - **In the code**, `et.rz.server.main/hub-identity-problem` refuses when the
     hub that answered `/health` is on the wrong machine. It covers a `server`
     started **any other way** -- by hand, from a make target, by a supervisor,
     or on the hub's own machine, where no such script is what is running.

   Neither subsumes the other, and to anyone reading them cold they will look
   redundant. **That is what this test exists for.** Deleting one because the
   other exists reopens the half it did not cover, and the half it did not cover
   is not visible from the line being deleted. So each guard names the other,
   and this fails if either the guard or the naming goes.

   Same job as `poller-placement-test`, which pins the two poller predicates
   against each other across four named worlds rather than against their own
   docstrings. A comment saying \"see the other one\" rots the moment the other
   one is gone; a test does not.

   ## The shell half is a requirement now, not an implementation

   It used to be two shell functions printed in the README, and this test
   extracted `rhizome-stop` and **ran** it against a stubbed `lsof` and a `kill`
   that could be told to fail. That is gone, and not because it stopped being a
   good test: the README is public and those functions were one person's own
   operating setup -- his deploy path, his machine names, the app he opens at
   the end of them -- so they moved to a private place and the README now states
   the *requirements* an implementation has to meet.

   **Which means the shell guard is no longer testable from here at all.** The
   thing that actually runs on an operator's machine is not in this repository
   and no assertion can reach it. What is left to pin is that the README goes on
   *asking* for it, in enough detail to be implementable, and that the two halves
   go on naming each other. That is weaker than executing it, it is named here
   rather than left for someone to discover, and it is the honest ceiling once
   the implementation is private.

   The README is read from the working directory rather than the classpath,
   because it is not on it and is not in the jar. `make test` runs from the repo
   root, which is the only place this can be checked from at all."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [et.rz.server.main :as server]))

(defn- readme []
  (let [f (io/file "README.md")]
    (assert (.isFile f)
            (str "README.md not found in " (System/getProperty "user.dir")
                 " -- run the tests from the repo root"))
    (slurp f)))

(def ^:private requirements-start
  "Where the README's list of what a start/stop pair must do begins. Held here
   so that renaming the headings is a deliberate two-place edit rather than
   something that quietly unpins the whole section."
  "**Starting**")

(def ^:private requirements-end
  "And where it ends -- the line that follows the list."
  "and then use")

(defn- requirements
  "The README's requirements for a start/stop pair, as text, whitespace
   normalised so that an assertion is not hostage to where a line wrapped.

   Located rather than searched for as a whole: asking the README *as a whole*
   whether it says `ssh` somewhere would be satisfied by the tunnel section,
   which is a different subject entirely. The same trap the code-side assertion
   below fell into once."
  []
  (let [src   (readme)
        start (str/index-of src requirements-start)
        end   (str/index-of src requirements-end)]
    (is (some? start) "the README no longer has a **Starting** requirements list")
    (is (and start end (< start end))
        "the README's requirements list no longer ends where this expects")
    (when (and start end (< start end))
      (str/replace (subs src start end) #"\s+" " "))))

(deftest the-shell-requirement-is-still-stated-test
  (let [r (requirements)]
    (testing "the README still asks for a holder check on :3008"
      (is (str/includes? (str r) "lsof -nP -iTCP:3008 -sTCP:LISTEN -F c")
          (str "the README no longer tells an operator how to ask *what* holds "
               ":3008. This is the only check that fires before any process "
               "starts, and the only one that does not have to ask the suspect "
               "process about itself -- and since the implementation is now "
               "private, this requirement is the only trace of it left in the "
               "repository."))
      (is (re-find #"(?i)not `?ssh" (str r))
          (str "the README no longer says the holder must be ssh. A leftover hub "
               "answers /health exactly as well as the tunnel does; only the "
               "port's holder tells them apart.")))
    (testing "and it says this is not the only line of defence"
      (is (str/includes? (str r) "hub-identity-problem")
          (str "the README's requirements no longer name the code-side guard. "
               "The two look redundant; this naming is what stops the next "
               "reader dropping one as a duplicate of the other.")))))

(deftest the-stop-requirements-are-still-stated-test
  (let [r (requirements)]
    (testing "killing :3008 is not gated on the marker"
      (is (re-find #"(?i)kill `?:3008`? whether or not" (str r))
          (str "the README no longer requires :3008 to be killed regardless of "
               "the marker. That gate failed exactly where it was needed: remove "
               "the marker before stopping the hub -- the natural order, and what "
               "*Moving the hub* asks for -- and the one function that could kill "
               "that hub refuses to. A keepalive replaces a killed tunnel in "
               "seconds; an orphan hub serves a stale database forever.")))
    (testing "and the two outcomes stay distinguishable"
      (is (str/includes? (str r) "which of the two was killed")
          "the README no longer requires the tunnel and the hub to be told apart")
      (is (re-find #"(?i)kill that failed" (str r))
          (str "the README no longer requires a failed kill to be reported as a "
               "failure. Reporting success over a hub that is still running is "
               "the failure this section exists to prevent, wearing a reassuring "
               "sentence.")))))

(def ^:private cross-reference-heading
  "How `hub-identity-problem`'s docstring labels the section about the other
   guard. Held here rather than inlined so that renaming the heading is a
   deliberate two-place edit and not something that quietly unpins the check."
  "not the only line of defence")

(defn- cross-reference-section
  "The part of a docstring from `cross-reference-heading` to its end, or nil,
   whitespace normalised.

   The point is to ask about the cross-reference *specifically*, rather than
   about the docstring as a whole -- a name that appears somewhere in a long
   docstring proves nothing about the paragraph that was supposed to carry it."
  [doc]
  (when-let [i (str/index-of (str doc) cross-reference-heading)]
    (str/replace (subs (str doc) i) #"\s+" " ")))

(deftest the-code-guard-is-still-there-and-names-the-shell-one-test
  (let [doc (:doc (meta #'server/hub-identity-problem))]
    (testing "both directions of the rule are live, not just the one in the report"
      (is (= :not-elected-but-hub-is-here
             (:problem (server/hub-identity-problem
                         {:elected? false :mine "a" :theirs "a" :url "u"}))))
      (is (= :elected-but-hub-is-elsewhere
             (:problem (server/hub-identity-problem
                         {:elected? true :mine "a" :theirs "b" :url "u"})))))
    (testing "and its docstring still carries the section that names the other guard"
      ;; Not `(includes? doc "…")` over the whole docstring, and the difference
      ;; is the whole value of this assertion. It used to check for the name of
      ;; a shell function anywhere in the docstring -- which also appeared,
      ;; incidentally, in the narrative of the failure. The entire
      ;; cross-reference section could therefore be deleted, which is exactly
      ;; what this test exists to prevent, and the suite stayed green. Verified
      ;; by doing it. So the section is located first, and the naming asserted
      ;; *inside* it.
      (let [section (cross-reference-section doc)]
        (is (some? section)
            (str "hub-identity-problem's docstring no longer has a section saying "
                 "this is not the only line of defence. Reword it as you like, but "
                 "keep a heading this can find -- if you renamed it, rename "
                 "`cross-reference-heading` here with it, deliberately."))
        (is (str/includes? (str section) "Package, deploy and run")
            (str "hub-identity-problem no longer points at the README section that "
                 "states the shell-side requirement. That section is now the only "
                 "place the shell guard exists in this repository -- the "
                 "implementation is private -- so a docstring that stops naming it "
                 "leaves the next reader with no way to find the other half."))))))

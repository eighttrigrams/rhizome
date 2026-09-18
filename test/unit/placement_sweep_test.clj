(ns placement-sweep-test
  "Pins `placement` to the source it describes.

   `placement` claims two things by name, and a name is exactly what rots:

   1. that its two command sets, together, cover **dispatch's list exactly** --
      so a `/ui` command added to `dispatch.clj` cannot default into a half in
      silence. That is the whole point of classifying both halves explicitly
      instead of deriving the hub side (see `placement/hub-commands`);
   2. that its route table names **the routes `server.clj` actually mounts** --
      so a route added, renamed or dropped there cannot leave the table stale.

   Both are checked by reading the source forms of those two namespaces off the
   classpath, because there is nothing to ask at runtime: `defdispatch` compiles
   to a `case`, and compojure routes compile to a closure. Neither keeps a
   registry to enumerate. Reading the source is the only way to make these
   assertions about the real list rather than about a copy of it -- and it works
   from the jar, where the .clj files are resources too.

   This test loads neither `dispatch` nor `server`, which is why it is a unit
   test: it needs no db, no config and no port."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [placement :as placement]))

;; --- reading source off the classpath ---------------------------------------

(defn- forms
  "Every top-level form of a namespace's source file, read as data."
  [resource-name]
  (let [r (io/resource resource-name)]
    (assert r (str "not on the classpath: " resource-name))
    (with-open [pr (java.io.PushbackReader. (io/reader r))]
      (doall (take-while #(not= ::eof %)
                         (repeatedly #(read {:eof ::eof :read-cond :allow} pr)))))))

(defn- find-form
  "The first top-level form whose head matches `pred`."
  [resource-name pred]
  (first (filter #(and (seq? %) (pred %)) (forms resource-name))))

;; --- 1. the dispatch list ---------------------------------------------------

(def ^:private dispatch-commands
  "Dispatch's command list, read from `(defdispatch handler* {..} & names)`:
   the names are everything after the handler symbol and the options map."
  (let [form (find-form "dispatch.clj" #(= 'defdispatch (first %)))]
    (assert form "no defdispatch form in dispatch.clj")
    (set (map str (drop 3 form)))))

(deftest the-two-halves-cover-dispatch-exactly-test
  (testing "every /ui command is placed, and nothing is placed twice"
    (is (seq dispatch-commands) "sanity: the list was actually read")
    (is (empty? (set/intersection placement/machine-local-commands
                                  placement/hub-commands))
        "a command placed in both halves has no defined home")
    (is (empty? (set/difference dispatch-commands
                                (set/union placement/machine-local-commands
                                           placement/hub-commands)))
        (str "a command was added to dispatch.clj without being placed. "
             "Decide which machine runs it: the hub (it only touches items, or "
             "only synced folders), or this machine (it touches THIS disk -- an "
             "OS open, a temp file, an upload, bytes for this browser). "
             "See placement's namespace docstring."))
    (is (empty? (set/difference (set/union placement/machine-local-commands
                                           placement/hub-commands)
                                dispatch-commands))
        "placement names a command the dispatcher does not answer to")))

(deftest every-dispatch-command-is-classified-test
  (testing "the predicate agrees with the sets, for every real command"
    (is (every? placement/classified? dispatch-commands))
    (is (not (placement/classified? "no-such-command"))
        "unclassified must stay false -- it is what the proxy will refuse on")))

;; --- 2. the route table -----------------------------------------------------

(def ^:private route-verbs #{"GET" "POST" "PUT" "DELETE" "PATCH" "HEAD" "ANY"})

(def ^:private api-mounts
  "Function names whose call mounts `/api`. See `mounted-routes`."
  #{"rest-routes" "rest-surface"})

(defn- mounted-routes
  "The route paths mounted at the top level of `server.clj`'s `routes`.

   Walks the form, collecting the path literal of every compojure verb and of
   every *nested* `context` -- without descending into a nested context, since
   its children (`/ui`'s inner `POST \"/\"`) are parts of it, not routes of
   their own. `root?` marks the enclosing `(context \"/\" [] ...)`, which is
   descended into rather than collected.

   `/api` is mounted by a *call*, not by a path literal, so the call is
   translated to the path it mounts -- the one hand-written rule here. Two names
   qualify, because step 3 put a chooser in front of the route table:
   `rest-surface` (server's own, which forwards to the hub or answers locally)
   and `rest-routes` (the table itself, still mounted directly by the hub). The
   rule fails loudly if either is renamed, because then no `/api` shows up at
   all and the sanity assertion below goes red."
  [form root?]
  (cond
    (and (seq? form) (symbol? (first form)))
    (let [h (name (first form))]
      (cond
        (= h "context")     (if root?
                              (mapcat #(mounted-routes % false) (drop 3 form))
                              [(second form)])
        (route-verbs h)     (when (string? (second form)) [(second form)])
        (api-mounts h)      ["/api"]
        :else               (mapcat #(mounted-routes % false) form)))
    (coll? form) (mapcat #(mounted-routes % false) form)
    :else nil))

(def ^:private server-routes
  (let [form (find-form "server.clj" #(and (#{'defn 'defn-} (first %))
                                           (= 'routes (second %))))]
    (assert form "no (defn- routes ...) form in server.clj")
    (set (mounted-routes (first (drop 3 form)) true))))

(deftest the-route-table-matches-the-mounted-routes-test
  (testing "placement/route-placement covers server.clj's routes exactly"
    (is (contains? server-routes "/api")
        (str "sanity: nothing in server.clj's routes was recognised as mounting "
             "/api. If the function that does was renamed, api-mounts needs the "
             "new name -- silently losing /api here would make the rest of this "
             "test pass for the wrong reason."))
    (is (= server-routes (set (keys placement/route-placement)))
        (str "server.clj's routes and placement/route-placement have drifted. "
             "A new route needs a half: :local (this machine answers it), "
             ":hub (proxied whole) or :split (dispatched per call)."))))

(deftest only-ui-is-split-test
  (testing "/ui is the only surface dispatched per call"
    (is (= #{"/ui"} (set (keep (fn [[path half]] (when (= :split half) path))
                               placement/route-placement)))
        (str "/api answering as one surface on the hub is a finding, not an "
             "accident -- see the comment in placement. If a second surface "
             "becomes split, that comment needs revisiting."))
    (is (= #{:local :hub :split} (set (vals placement/route-placement)))
        "sanity: all three halves are in use; an unused one means a stale table")))

;; --- 3. the byte paths that middleware hides ---------------------------------

(deftest the-image-middleware-is-still-there-test
  (testing "/imgs/* is middleware, not a route, so the table cannot see it"
    (let [app-form (find-form "server.clj" #(and (#{'defn 'defn-} (first %))
                                                 (= 'app (second %))))
          syms     (set (map str (filter symbol? (tree-seq coll? seq app-form))))]
      (is (contains? syms "wrap-imgs")
          (str "wrap-imgs is how images and previews reach the browser, and it "
               "is the largest byte path in the app. It is wrapped around the "
               "routes rather than mounted as one, so route-placement cannot "
               "pin it -- this assertion is its stand-in. If it moved or was "
               "renamed, placement's claim that image bytes stay local needs "
               "re-checking."))
      (is (contains? syms "wrap-resource")
          "the frontend bundle is served from the classpath by this machine"))))

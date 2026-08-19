(ns api.replica-query-sweep-test
  "Pins the read-only classification in `dispatch` to two things it only claims
   by name:

   1. the dispatch list -- every command classified as a query must be a command
      the dispatcher actually answers to, so a renamed or dropped command cannot
      leave a stale entry behind;
   2. the write ban itself -- every classified query is executed against a
      datasource opened with :read-only? true, the way a replica's is. A listed
      query that later grows a write comes back as SQLITE_READONLY here instead
      of in the owner's face.

   The classification failing closed for *added* commands is covered by
   api.replica-test (unclassified-command-is-refused-test); this is the other
   direction."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [next.jdbc :as jdbc]
            [api.harness :as harness]
            [config :as config]
            [datastore.connection :as connection]
            [datastore.schema :as schema]
            [dispatch :as dispatch]
            [et.vp.ds :as ds]
            [opener :as opener]
            [semsearch.backfill :as backfill]
            [semsearch.embedder :as embedder])
  (:import [java.io File]))

(def ^:private classified-queries
  "dispatch's own whitelist -- read from the var so the two cannot drift."
  @#'dispatch/read-only-commands)

(defn- query-calls
  "Every classified query, with the args the SPA sends it, as
   command -> the arg vectors worth exercising. Keys must cover
   `classified-queries` exactly (asserted below), so a command added to the
   whitelist has to be swept too.

   `list-resources` gets one entry per query :cmd branch: those branches are the
   part of the classification that is not name-deep (see
   dispatch/writing-list-resources-cmds), so each has to be shown not to write."
  [{:keys [ctx item]}]
  {"list-resources"
     [[{}]
      [{:active-search :items :q ""}]
      [{:active-search :contexts :q ""}]
      [{:cmd :link-item-to-selected-item :selected-item ctx}]
      [{:cmd :start-linking-selected-item-to-context :selected-item item}]
      [{:cmd :start-context-search :selected-item ctx}]]
   "fetch-aggregated-contexts" [[{:selected-item ctx}]]
   "fetch-context"             [[{} [{:id (:id ctx)} false]]
                                [{} [{:id (:id item)} true]]]
   "deselect-context"          [[{}]]
   "select-last-context"       [[{:old-selected-item ctx}]]
   "fetch-item-description"    [[{} {:id (:id item)}]]
   ;; Swept against the context rather than the item: `seed!` gives the context
   ;; a description and a revision to go with it, so this is the one of the two
   ;; that actually has a history to assess.
   "fetch-item-provenance"     [[{} {:id (:id ctx)}]
                                [{} {:id (:id item)}]]
   ;; Both directions of the one edge `seed!` makes: the one that is there, and
   ;; one that is not. A read for a relation nobody wrote must stay a read.
   "fetch-relation-description" [[{} {:item-id (:id item) :context-id (:id ctx)}]
                                 [{} {:item-id (:id ctx) :context-id (:id item)}]]
   "get-obsidian-file-content" [[{}]]
   "discard-obsidian-changes"  [[{}]]
   "list-youtube-poll-channels" [[{}]]
   "list-atom-poll-feeds"      [[{}]]
   ;; The two vector searches need the sqlite-vec extension, so they are swept in
   ;; their own ^:vector test below.
   "vector-search-related-items"
     [[{:selected-item ctx :q "history of oil"}]]
   "vector-threshold-search-related-items"
     [[{:selected-item ctx :q "history of oil" :vector-threshold 0.5}]]})

(def ^:private vector-queries
  #{"vector-search-related-items" "vector-threshold-search-related-items"})

(defn- temp-db-path
  []
  (let [f (File/createTempFile "rhizome-replica-sweep" ".db")]
    (.delete f)
    (.getPath f)))

(defn- delete-db!
  [path]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (.delete (File. (str path suffix)))))

(defn- seed!
  "A context with one related item, plus a description revision so the history
   reads are not trivially empty. Written through a normal datasource -- this is
   the primary's side of the sync."
  [db]
  (let [ctx (ds/new-context db {:title "Books"})
        item (ds/new-item db "Sapiens" "" #{(:id ctx)} 1)]
    (ds/update-context-description db {:id (:id ctx) :description "a shelf"} "app")
    ;; So the vector sweep has something to match against rather than querying an
    ;; empty items_vec. Without the extension there is no table to write to.
    (when connection/vec-available?
      (backfill/store-embedding! db (:id item) (vec (repeat embedder/embedding-dim 0.1))))
    {:ctx (ds/get-item db {:id (:id ctx)})
     :item (ds/get-item db {:id (:id item)})}))

(defn- call-error
  "The error the dispatcher answered with, as a string, or nil when the call went
   through: `call-on!` turns a :thrown envelope into ex-info."
  [db fn-name args]
  (try (apply harness/call-on! db fn-name args)
       nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(defn- sweep!
  "Run `names` against a read-only datasource over a freshly seeded db, in replica
   mode -- both guards on at once, exactly as they are on a replica. Returns
   [command args error-or-nil] for every call made."
  [names]
  (let [path (temp-db-path)]
    (try
      (let [seeded (let [rw (connection/make-datasource {:dbname path})]
                     (schema/apply-schema! rw)
                     (seed! rw))
            ro (connection/make-datasource {:dbname path :read-only? true})]
        ;; Only the db and the role change: the rest of the config stays as the
        ;; test env has it, so the query paths see what they normally do.
        ;;
        ;; The one exception is the Obsidian temp file: `discard-obsidian-changes`
        ;; calls opener/delete-obsidian-temp-file, which deletes a path hardcoded
        ;; into the owner's vault -- on the owner's machine that is a real in-flight
        ;; edit, so a test run must not be able to reach it. Stubbing the delete
        ;; keeps what this sweep is about (the command still runs against the
        ;; read-only db) and drops only the filesystem side effect, which could not
        ;; write a db row anyway.
        (with-redefs [config/config (assoc config/config :db ro :read-only-replica? true)
                      opener/delete-obsidian-temp-file (fn [] nil)]
          (doall
            (for [[command arg-vectors] (query-calls seeded)
                  :when (contains? names command)
                  args arg-vectors]
              [command args (call-error ro command args)]))))
      (finally (delete-db! path)))))

(defn- report [command args] (str command " " (pr-str args)))

(defn- unknown-function?
  "Whether the dispatcher has no such command. Asks by calling it deliberately
   without args: an unknown command answers \"Unknown function: '…'\", a known one
   an arity error, and nothing gets executed either way.

   The dispatcher logs every error it answers with at ERROR
   (dispatch/handle-error), so the arity errors provoked here -- one per
   classified query -- would print a stack trace each into every `make test` run
   and bury a real one. The log call is stubbed out for the duration; the
   :thrown envelope `call-error` reads is built by the dispatcher regardless of
   whether the handler logs, so what this test sees is unchanged."
  [command]
  (with-redefs [dispatch/handle-error (fn [_] nil)]
    (boolean (re-find #"Unknown function" (str (call-error nil command []))))))

(deftest classified-queries-are-dispatch-commands-test
  (testing "the whitelist names commands the dispatcher answers to -- no stale entries"
    (doseq [command classified-queries]
      (is (not (unknown-function? command)) command)))
  (testing "and the sweep below covers every one of them"
    (is (empty? (set/difference classified-queries (set (keys (query-calls {})))))
        "a command added to dispatch/read-only-commands needs an entry in query-calls")
    (is (empty? (set/difference (set (keys (query-calls {}))) classified-queries))
        "query-calls sweeps something that is no longer classified as a query")))

(deftest classified-queries-run-against-a-read-only-datasource-test
  (testing "no classified query writes: each one goes through against a read-only db"
    (let [results (sweep! (set/difference classified-queries vector-queries))]
      (is (seq results))
      (doseq [[command args error] results]
        (is (nil? error) (report command args))
        (is (not (re-find #"(?i)readonly" (str error)))
            (str "hit the write ban: " (report command args)))))))

(deftest ^:vector vector-queries-run-against-a-read-only-datasource-test
  ;; ^:vector keeps this out of `make test` where the sqlite-vec dylib is absent
  ;; (the tagging convention of semsearch.threshold-query-test); the explicit
  ;; guard keeps a direct `clj -X:test` run there honest too -- without the
  ;; extension there is no items_vec to sweep against. The embedder is stubbed so
  ;; nothing goes to the network.
  (when connection/vec-available?
    (testing "the two vector searches too"
      (with-redefs [embedder/embed-text (fn [_] (vec (repeat embedder/embedding-dim 0.1)))]
        (let [results (sweep! vector-queries)]
          (is (seq results))
          (doseq [[command args error] results]
            (is (nil? error) (report command args))
            (is (not (re-find #"(?i)readonly" (str error)))
                (str "hit the write ban: " (report command args)))))))))

(deftest read-only-datasource-really-is-the-ban-test
  (testing "the sweep would notice a write: the same datasource refuses one"
    (let [path (temp-db-path)]
      (try
        (let [rw (connection/make-datasource {:dbname path})]
          (schema/apply-schema! rw)
          (seed! rw))
        (let [ro (connection/make-datasource {:dbname path :read-only? true})
              e (try (jdbc/execute-one! ro ["update items set title = 'nope'"])
                     nil
                     (catch java.sql.SQLException e e))]
          (is (some? e))
          (is (re-find #"(?i)readonly" (.getMessage e))))
        (finally (delete-db! path))))))

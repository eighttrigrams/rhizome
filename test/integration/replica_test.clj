(ns replica-test
  "The replica guards that live in `server`: the startup steps, the polling job
   and /upload."
  (:require [clojure.test :refer [deftest is testing]]
            [config :as config]
            [datastore.schema :as schema]
            [db :as db]
            [role :as role]
            [hub-proxy :as hub-proxy]
            [poll :as poll]
            [server :as server]
            [upload :as upload]))

(def ^:private replica-config {:read-only-replica? true})
(def ^:private primary-config {:read-only-replica? false})

(deftest poll-scheduling-decision-test
  (testing "the pollers write items and seen-rows, so a replica does not schedule them"
    (with-redefs [config/config replica-config]
      (is (false? (server/poll-scheduling-enabled?)))))
  (testing "a primary does"
    (with-redefs [config/config primary-config]
      (is (true? (server/poll-scheduling-enabled?)))))
  (testing "and e2e still does not, replica or not"
    (with-redefs [config/config (assoc primary-config :e2e? true)]
      (is (false? (server/poll-scheduling-enabled?))))))

(deftest replica-never-starts-the-scheduler-test
  (let [started (atom [])
        record! (fn [db] (swap! started conj db))]
    (testing "startup skips the job entirely on a replica -- it is not scheduled and then blocked"
      (with-redefs [config/config replica-config
                    poll/start-scheduler! record!]
        (#'server/start-pollers!)
        (is (empty? @started))))
    (testing "a primary schedules it"
      (with-redefs [config/config (assoc primary-config :db ::db)
                    poll/start-scheduler! record!]
        (#'server/start-pollers!)
        (is (= [::db] @started))))))

(deftest write-side-startup-skipped-on-a-replica-test
  (let [called (atom [])
        record! (fn [k] (fn [& _] (swap! called conj k) nil))]
    ;; Since step 4 the seed is the hub's (db-server/-main), so what is left
    ;; here is the ImageMagick gate alone -- the one startup step that is about
    ;; this machine rather than about the database.
    (testing "a replica runs it not at all: the gate would refuse it a boot over
              preview downscaling it can never do"
      (with-redefs [config/config replica-config
                    upload/ensure-convert! (record! :ensure-convert)
                    schema/apply-schema! (record! :apply-schema)]
        (#'server/prepare-for-writing!)
        (is (empty? @called))))
    (testing "a primary runs it"
      (with-redefs [config/config (assoc primary-config :db ::db)
                    upload/ensure-convert! (record! :ensure-convert)
                    schema/apply-schema! (record! :apply-schema)]
        (#'server/prepare-for-writing!)
        (is (= [:ensure-convert] @called))))))

(deftest the-app-server-does-not-apply-the-schema-test
  ;; Since step 4 the db-server owns the file and applies the schema as it opens
  ;; it, before this process is started at all. `apply-schema!` is still redefined
  ;; in the test above, so this says the difference out loud rather than leaving
  ;; it to be read out of an absent keyword in a vector.
  (let [called (atom [])]
    (with-redefs [config/config (assoc primary-config :db ::db)
                  upload/ensure-convert! (constantly nil)
                  schema/apply-schema! (fn [& _] (swap! called conj :apply-schema) nil)]
      (#'server/prepare-for-writing!)
      (is (empty? @called)
          "a primary app-server applying the schema would be a second opinion about
           a file it does not open"))))

(deftest upload-refused-on-a-replica-test
  (testing "/upload is a write, so it gets the graceful refusal"
    (with-redefs [config/config replica-config]
      (let [resp (server/upload-handler {:multipart-params {}})]
        (is (= 403 (:status resp)))
        (is (re-find #"read-only replica" (:body resp)))))))

;; --- the two processes have to agree about the role -------------------------
;; Same rule (`role`), same marker file, but each process reads it in its own
;; working directory -- and only the MARKER is shared by construction. `:dev?`
;; is per-file, so a db-server started against a standalone `{:db-server {…}}`
;; config reads no `:dev?`, calls itself prod, finds no marker beside itself and
;; opens the database read-only, under an app-server that believes it may write.
;;
;; That combination is invisible until the first write: `SELECT 1` succeeds on a
;; read-only database, `/api/status` says `read-only-replica: false`, and the
;; failure arrives later as a bare SQLITE_READONLY. So the verdicts are compared
;; at startup instead.

(def ^:private hub-url
  "A url nothing is listening on. Every use of it here is redefined away -- what
   the tests need is a `:hub-url` that is set, not one that answers."
  "http://127.0.0.1:65535")

(defn- role-check-with
  "Run the startup role check. `app` is what THIS process's world says --
   `:dev?`, and whether the marker is beside it -- and `db-read-only?` is what
   the db-server answers. Answers the exception, or nil.

   The app's verdict is **derived with the real rule** rather than passed in, so
   a fixture cannot describe a world that could not exist. It could before: the
   verdict was a boolean set independently of the `:dev?` and the marker the
   message is built from, and the first version of this helper duly asserted
   against a config that said primary and prod-without-marker at once."
  [app db-read-only?]
  (let [{:keys [dev? marker?]} app
        app-replica? (role/read-only-replica? {:dev? dev?} (boolean marker?))]
    (with-redefs [config/config                  {:hub-url hub-url
                                                  :read-only-replica? app-replica?
                                                  :dev? dev?}
                  config/primary-marker-present? (constantly (boolean marker?))]
      ;; The db-server's verdict arrives in its /health answer now, which is the
      ;; same call the reachability check already makes -- one question, one
      ;; round trip, instead of a statement and then a health read.
      (try (#'server/check-db-server-role! hub-url {:ok true :read-only? db-read-only?})
           nil
           (catch Throwable t t)))))

;; The three worlds, named once. `dev` is the flagship repro: a dev app-server
;; in front of a db-server that read a config.edn of its own.
(def ^:private dev-primary     {:dev? true})
(def ^:private prod-primary    {:dev? false :marker? true})
(def ^:private prod-replica    {:dev? false :marker? false})

(deftest the-app-and-its-db-server-must-agree-about-the-role-test
  (testing "a read-only db-server under an app that thinks it may write is refused"
    ;; The standalone-config case, and the one that used to boot fully green.
    (let [t (role-check-with dev-primary true)]
      (is (some? t))
      (is (re-find #"disagree about whether this instance may write" (.getMessage t)))
      (is (re-find #"app-server: primary" (.getMessage t))
          "the message names what THIS process decided")
      (is (re-find #"db-server:  read-only" (.getMessage t))
          "and what the other one did")
      (is (re-find #"primary\.nosync" (.getMessage t))
          "and the marker both of them looked for")))
  (testing "and the milder half -- a writable db-server under a replica -- too"
    ;; Less dangerous (the app refuses every write in front of it) but the same
    ;; confusion, and the same fix.
    (let [t (role-check-with prod-replica false)]
      (is (some? t))
      (is (re-find #"app-server: read-only replica" (.getMessage t)))
      (is (re-find #"db-server:  writable" (.getMessage t)))))
  (testing "two primaries agree, and two replicas agree"
    (is (nil? (role-check-with dev-primary false)))
    (is (nil? (role-check-with prod-replica true)))))

(deftest the-refusal-says-truthfully-why-this-process-is-what-it-is-test
  ;; A primary is a primary for one of TWO reasons -- dev mode, or prod plus the
  ;; marker -- and the first version of this message asserted the second for
  ;; both. In the very scenario it was written for (a dev app-server in front of
  ;; a standalone db-server) that made it doubly false: it denied the mode the
  ;; reader is in and sent them looking for a marker file that is not there.
  ;; Which is the failure the F2 fix was about, shipped by the F1 fix.
  (testing "dev mode: never a replica, and no marker is claimed"
    (let [m (.getMessage (role-check-with dev-primary true))]
      (is (re-find #"primary -- dev mode" m))
      (is (re-find #"never a\s+replica: no marker is consulted" m))
      (is (not (re-find #"prod mode and primary\.nosync" m))
          "the old message claimed exactly this, with :dev? true and no marker on disk")
      (testing "and it names the remedy, which is what the reader actually needs"
        (is (re-find #"Add :dev\? true to that file" m)))))
  (testing "prod with the marker: primary, and it says where the marker is"
    (let [m (.getMessage (role-check-with prod-primary true))]
      (is (re-find #"primary -- prod mode and primary\.nosync in " m))
      (is (not (re-find #"dev mode" m)))))
  (testing "prod without it: the replica case, which was right all along"
    (let [m (.getMessage (role-check-with prod-replica false))]
      (is (re-find #"read-only replica -- prod mode and no primary\.nosync in " m)))))

(deftest the-startup-check-consults-both-halves-test
  ;; The one part of the F1 wiring nothing covered: `check-db-server-role!` was
  ;; tested directly, so deleting its CALL SITE in `check-db-server!` left the
  ;; suite green. e2e does not catch it either -- there the two agree, and
  ;; agreement is silent whether or not anything asked.
  (testing "the role check is reached: a disagreement refuses through the front door"
    (let [t (with-redefs [config/config        {:hub-url hub-url :dev? true :read-only-replica? false}
                          config/primary-marker-present? (constantly false)
                          ;; The role now rides in on the same /health answer,
                          ;; so the disagreement is expressed by what /health
                          ;; says rather than by a second call.
                          hub-proxy/health     (constantly {:ok true :read-only? true})]
              (try (#'server/check-db-server!) nil (catch Throwable t t)))]
      (is (some? t)
          "if this passes, `check-db-server!` is no longer calling the role check")
      (is (re-find #"disagree about whether this instance may write" (.getMessage t)))))
  (testing "and so is the reachability check, at the url it claims to ask"
    ;; It asks /health now rather than running SELECT 1 over the statement
    ;; protocol (step 4): the same question -- something is listening and it has
    ;; a database open -- without this process having to speak SQL to ask it.
    (let [asked (atom [])]
      (with-redefs [config/config        {:hub-url hub-url :dev? true :read-only-replica? false}
                    config/primary-marker-present? (constantly false)
                    hub-proxy/health     (fn [url] (swap! asked conj url)
                                           {:ok true :read-only? false})]
        (#'server/check-db-server!))
      (is (= [hub-url] @asked))))
  (testing "no :hub-url means nothing is asked at all -- which is what test mode rests on"
    (let [asked (atom [])]
      (with-redefs [config/config        {:hub-url nil :read-only-replica? false}
                    hub-proxy/health     (fn [& _] (swap! asked conj :health) nil)]
        (#'server/check-db-server!))
      (is (empty? @asked)))))

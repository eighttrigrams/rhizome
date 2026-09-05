(ns role
  "Primary or read-only replica, decided from the directory a process was
   started in.

   **Two processes ask this now, and they have to reach the same verdict.**
   The app-server's graceful refusals sit in front of every write; the
   db-server opens the database read-only, which is what makes the ban
   structural. Both read the same marker in the same directory, independently
   -- so the marker's name and the rule that reads it live here, once, rather
   than in each of them.

   It is deliberately tiny and requires nothing but `clojure.java.io`. That is
   what lets the db-server use it: `config` cannot be required from there,
   because loading `config` builds the *app's* configuration -- folders,
   logging and all -- out of a file that, in the separate-files arrangement,
   holds nothing but the `:db-server` section.

   `config` re-exports all three names, so `config/primary-marker` and
   `config/read-only-replica?` go on meaning what they always did."
  (:require [clojure.java.io :as io]))

;; --- primary vs replica -----------------------------------------------------
;; The owner syncs the rhizome directory between machines, and the sync
;; excludes files ending in `.nosync`. A marker named `primary.nosync` in the
;; directory the app starts from (sibling to config.edn) therefore exists on
;; exactly one machine -- the primary. Every other, synced copy is a replica
;; and must never write to the db.
(def primary-marker
  "File name of the primary marker, looked up in the start directory."
  "primary.nosync")

(defn primary-marker-present?
  "Is the primary marker in the directory the app was started from? The path is
   relative (like config-path), so it resolves against the process's working
   directory."
  ([] (primary-marker-present? (str "./" primary-marker)))
  ([path] (.exists (io/file path))))

(defn read-only-replica?
  "Must this instance run as a read-only replica? True in prod mode when the
   primary marker is absent.

   Evaluated exactly ONCE per process -- in `config/ds`, which `config` calls at
   namespace load, and in `db-server`'s `-main` -- and then held for the process
   lifetime: an instance's role does not flip mid-run. Nothing re-reads the
   filesystem afterwards, so a sync that adds or drops the marker underneath a
   running app cannot silently change what that process may do. Promoting a
   replica to primary means placing `primary.nosync` next to config.edn and
   RESTARTING BOTH PROCESSES; a primary likewise stays one until it is
   restarted without the marker.

   Dev mode (including :test? / :e2e?, which force :dev? true) is never a
   replica: no marker needed, no guards, no banner.

   `c` is the config map. The app hands over its own, fully resolved; the
   db-server hands over what it read from `config.edn`, where `:dev?` is the
   only key outside its own section it reads at all -- because the rule needs
   it, and because a mode both processes are in is not either one's private
   configuration."
  [c marker-present?]
  (and (not (:dev? c)) (not marker-present?)))

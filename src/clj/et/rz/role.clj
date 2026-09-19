(ns et.rz.role
  "Which machine runs the hub, decided from the directory a process was started
   in.

   The owner syncs the rhizome directory between machines, and the sync excludes
   files ending in `.nosync`. A marker named `primary.nosync` next to config.edn
   therefore exists on **exactly one machine**, and that machine is the one that
   holds the database.

   ## The marker elects; it used to demote

   Until step 4 of the architecture rework this answered a different question:
   *may this instance write*. Present, and the database opened writable; absent,
   and prod mode meant a read-only replica -- a synced copy that refused every
   write, in front and at the driver both. That is why the app-server and the
   db-server each read it independently, and why they had to be checked against
   each other at boot.

   There are no replicas now: one hub, and a `server` on every machine that
   forwards to it. So the marker was free, and it answers the question the
   deployment actually has -- **which machine runs the hub**. It is read in one
   place, `db-server/check-elected!`, which refuses to boot a hub on a machine
   that has no marker.

   It stays deliberately tiny and requires nothing but `clojure.java.io`. That
   is what lets the db-server use it: `config` cannot be required from there,
   because loading `config` builds the *app's* configuration -- folders, logging
   and all -- out of a file that, in the separate-files arrangement, holds
   nothing but the `:db-server` section.

   `config` re-exports both names."
  (:require [clojure.java.io :as io]))

(def primary-marker
  "File name of the marker, looked up in the start directory."
  "primary.nosync")

(defn primary-marker-present?
  "Is the marker in the directory the process was started from? The path is
   relative (like config-path), so it resolves against the working directory.

   Read once, at boot, and then held: a sync that adds or drops the marker
   underneath a running process does not change what that process is. Moving
   the hub to another machine means stopping the hub on the old one, moving the
   database file, placing the marker on the new one and starting it there --
   see the README's run section."
  ([] (primary-marker-present? (str "./" primary-marker)))
  ([path] (.exists (io/file path))))

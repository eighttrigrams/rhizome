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
   hub each read it independently, and why they had to be checked against
   each other at boot.

   There are no replicas now: one hub, and a `server` on every machine that
   forwards to it. So the marker was free, and it answers the question the
   deployment actually has -- **which machine runs the hub**. It is read in one
   place, `et.rz.hub.main/check-elected!`, which refuses to boot a hub on a machine
   that has no marker.

   It stays deliberately tiny and requires nothing but `clojure.java.io`. That
   is what lets the hub use it: `config` cannot be required from there,
   because loading `config` builds the *app's* configuration -- folders, logging
   and all -- out of a file that, in the separate-files arrangement, holds
   nothing but the `:hub` section.

   ## And which machine *this* is

   `hostname` answers that, and it is here rather than in either main for the
   reason the marker is: **both processes have to reach the same verdict about
   the same machine**, and the only way to guarantee that is one function. The
   hub reports it on `/health` and the `server` compares it against its own --
   see `et.rz.server.main/hub-identity-problem`.

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

(defn- read-hostname
  "The machine's name, from the most deterministic source that answers.

   Order matters, and it is not the obvious one.

   1. **`hostname(1)`.** It reads the kernel's nodename and involves no name
      service at all, so two processes on one machine get the same answer no
      matter what the network is doing. That is the property the comparison
      needs; a *correct* fully-qualified name is not.
   2. **`InetAddress/getLocalHost`**, only if that failed. It does a lookup,
      which is exactly why it is second: it can throw on a machine whose
      hostname does not resolve, and on a laptop it can answer differently
      before and after the network comes up -- two processes started either
      side of that would disagree about a machine that never changed.
   3. **nil**, if neither answered. Deliberately not a placeholder string: a
      placeholder would compare *equal to another machine's placeholder*, which
      is the one wrong answer this whole mechanism exists to prevent. Callers
      must treat nil as \"cannot tell\" and refuse, not as a value."
  []
  (or (try (let [p (.start (doto (ProcessBuilder. ["hostname"])
                             (.redirectErrorStream true)))
                 out (with-open [r (io/reader (.getInputStream p))]
                       (.trim ^String (or (first (line-seq r)) "")))]
             (.waitFor p)
             (when-not (= "" out) out))
           (catch Throwable _ nil))
      (try (let [n (.trim ^String (str (.getHostName (java.net.InetAddress/getLocalHost))))]
             (when-not (= "" n) n))
           (catch Throwable _ nil))))

(def hostname
  "This machine's name, or nil when it could not be determined.

   Held rather than asked each time: it is read at boot by two processes that
   must agree, and a value that could change underneath one of them would make
   the agreement a coincidence. A machine's name does not change while rhizome
   is running, and if it did, the answer that matters is the one both processes
   started with."
  (delay (read-hostname)))

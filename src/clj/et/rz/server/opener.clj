(ns et.rz.server.opener
  "Handing a file to the OS to open.

   Obsidian support lived here too -- a temp file written into the owner's vault,
   opened over the `obsidian://` scheme, read back and deleted. It was removed
   on 2026-09-18 with the hub/server split: it was the only stateful local-file
   workflow in the app, and a hardcoded path in one machine's home directory
   cannot be a step in a command dispatched over a network. The cookbook recipe
   \"Obsidian round trip: editing an item's description in a real editor, with
   no API\" records how it worked and what to do differently if it comes back.

   Note that `description_source = \"obsidian\"` rows outlive the feature, and
   `provenance/us` still counts them as the owner's own hand. See the comment
   there before touching that set."
  (:require [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [et.rz.hub.repository.homefolder :as home]))

(defn open
  [file-id]
  (when-let [path (home/get-target file-id)]
    (when (.exists (io/file path)) (sh/sh "open" path))))

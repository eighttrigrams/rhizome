(ns et.rz.placement
  "**Machine placement**: which of the two halves a call belongs to.

   Nothing here runs yet -- this namespace is data plus one predicate, read by
   the sweep test (`api.placement-sweep-test`) and, later, by the `server`'s
   proxy. It is step 1 of `handoffs/RHIZOME_ARCH_REWORK_2.md`, and it exists
   before the split it describes so that the split is a lookup rather than a
   judgement call made forty times in a row.

   The two halves, in the vocabulary of `specs/architecture.md`:

   - the **hub** -- one instance, the machine holding the SQLite file. Owns
     items: the db, the schema, the vec extension, search, the embedder, the
     pollers, the scrapers.
   - the **`server`** -- one per machine. Owns *this* machine: the browser it
     serves, and the disk the human is sitting in front of.

   ## The axis is not \"touches the filesystem\"

   That was the first cut and it is wrong, because it puts `insert-item` on the
   local side -- the scrapers write preview images (see
   `et.rz.hub.repository.insertion.*` -> `upload/upload-preview-file`). Sorting by *whose*
   disk splits it correctly:

   1. **synced-folder access** -- `:preview-images`, `:folders`, the iCloud
      directories. Every machine has these already, through the iCloud sync
      that predates all of this work (`specs/architecture.md`, the app-server
      -> file folders edge), so the sync distributes the result and *any*
      machine may do the work. Note that this is about *files*; the db is in
      one place and stays there. Placement is then free, and the hub
      wins by default: it is where the accompanying db write happens, so doing
      it there keeps one user action to one round trip.
   2. **this-machine access** -- the OS `open` call, the Obsidian temp file, an
      HTTP multipart tempfile, bytes served to this browser. No sync can carry
      these; they are meaningless anywhere but the machine the human is at.

   Only (2) is machine-local. `machine-local-commands` is exactly the commands
   that need (2).")


(def machine-local-commands
  "The `/ui` commands that must run on the machine with the browser.

   **It is empty, and that is the current truth rather than an oversight.** Its
   only members were the four Obsidian commands (`edit-item-in-obsidian`,
   `sync-obsidian-changes`, `discard-obsidian-changes`,
   `get-obsidian-file-content`), and Obsidian support was removed on 2026-09-18
   -- see `opener`, and the cookbook recipe it points at. So every `/ui` command
   is the hub's, and `route-placement` says `/ui` is `:hub` rather than
   `:split`. `et.rz.placement-sweep-test` ties those two statements together, so they
   cannot come apart.

   **The set stays, and so does the mechanism.** What is left is not a leftover
   but the guard: `api.placement-sweep-test` demands that this set and
   `hub-commands` together cover dispatch's list exactly, so the next command
   that touches this machine's disk has to be named here rather than defaulting
   into the hub in silence -- where its file work would run against the wrong
   disk and nothing would say so. An empty whitelist with a live guard is worth
   more than a deleted one.

   What belongs here, if something does again: work on *this* disk that no sync
   can carry -- an OS `open`, a temp file the human edits, an HTTP multipart
   tempfile, bytes for this browser. Not work in a synced folder; see the
   namespace docstring for why that distinction is the whole axis."
  #{})

(def hub-commands
  "The `/ui` commands that run on the hub -- listed, not derived.

   Deriving this as \"everything not machine-local\" would be shorter by forty
   lines and wrong: a command added to `dispatch` would then default into the
   hub in silence, and a file-touching one placed there does its file work on
   the *wrong machine's* disk, which no error surfaces. Listing both halves
   makes `api.placement-sweep-test` able to demand that the union covers
   dispatch exactly, so a new command reddens a test until someone decides
   where it runs. The house rule (cookbook #12): a guard you cannot break is
   not a guard.

   The two sets are disjoint and exhaustive over dispatch's list; that is
   asserted, not assumed."
  #{"list-resources"
    "insert-item"
    "insert-context"
    "change-secondary-contexts-selection"
    "change-secondary-contexts-unassigned-selected"
    "change-secondary-contexts-inverted"
    "change-description-filter"
    "deselect-secondary-contexts"
    "finish-linking-item"
    "reprioritize-item"
    "cycle-search-mode"
    "store-current-view"
    "load-stored-context"
    "remove-stored-context"
    "delete-item"
    "unlink-item"
    "unlink-selected-item-from-container"
    "update-item"
    "fetch-aggregated-contexts"
    "select-last-context"
    "upgrade-item-to-context"
    "link-selected-context-to-context"
    "fetch-context"
    "deselect-context"
    "delete-context"
    "fetch-item-description"
    "fetch-item-provenance"
    "update-annotations"
    "fetch-relation-description"
    "fetch-relation-history"
    "fetch-relation-provenance"
    "vector-search-related-items"
    "vector-threshold-search-related-items"
    "list-youtube-poll-channels"
    "add-youtube-poll-channel"
    "delete-youtube-poll-channel"
    "update-youtube-poll-channel"
    "list-atom-poll-feeds"
    "add-atom-poll-feed"
    "delete-atom-poll-feed"})

(def route-placement
  "The top-level HTTP routes of `et/rz/server/main.clj`, each assigned a half. Three
   values, because two do not fit:

   - `:local` -- answered by this machine, never proxied;
   - `:hub`   -- proxied to the hub whole;
   - `:split` -- dispatched per call, by command name. **Nothing is `:split`
     today:** `/ui` was, until Obsidian support was removed and
     `machine-local-commands` became empty. The value stays because the
     dispatch-by-name mechanism stays (see `machine-local-commands`), and
     `et.rz.placement-sweep-test` re-derives `/ui`'s entry from whether that set has
     members, so the two cannot disagree.

   Keys are the route paths exactly as they appear in `et/rz/server/main.clj`, so
   `api.placement-sweep-test` can check that none has gone stale.

   Two that need a word:

   - `/img-by-id/:item-id` is `:local` although it starts with a db lookup: it
     ends by streaming bytes to this browser. It becomes a local handler that
     asks the hub for the path. Note this one is a *choice*, not a necessity --
     images live in synced folders, so the hub could serve them (namespace
     docstring, case 1). Local wins because a 1 MB image should not cross a
     tunnel to reach a browser two feet from the file.
   - `/test/reset` is `:hub` -- it truncates tables. It is e2e-only, and e2e
     runs both halves on one machine anyway, but classifying it by what it does
     rather than by where it is used keeps the table honest."
  {"/ui"                 :hub
   "/api"                :hub
   "/open/:file-id"      :local
   "/img-by-id/:item-id" :local
   "/upload"             :local
   "/test/reset"         :hub
   "/"                   :local})

;; `/api` is NOT split, and finding that out is most of what step 1 was for.
;;
;; The candidate was `GET /api/items/:id/images?data=true`, which answers with
;; base64 image bytes and looked machine-local for the same reason `/imgs/*` is.
;; It is not: images and previews live in the *synced* folders (`:images`,
;; `:preview-images`), so the hub holds them too and can answer the whole of
;; `/api` alone. Serving bytes locally is an optimisation, available later and
;; only for the browser's own routes, not a correctness requirement -- and
;; agents hitting `/api` through the tunnel are not latency-bound anyway.
;;
;; So the `/api` surface stays one surface, on the hub, and open question 3 of
;; the handoff ("does /api stay one surface?") answers itself: it never needed
;; to be two.

(defn machine-local-command?
  "Whether a `/ui` command name runs on this machine rather than the hub."
  [fn-name]
  (contains? machine-local-commands fn-name))


(defn classified?
  "Whether a `/ui` command has been placed at all. Later, the `server`'s proxy
   refuses an unclassified command rather than guessing a half -- loudly, on
   the machine that can still be told about it. The sweep test is what keeps
   this from ever being false in a release."
  [fn-name]
  (or (contains? machine-local-commands fn-name)
      (contains? hub-commands fn-name)))

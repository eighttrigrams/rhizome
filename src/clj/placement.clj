(ns placement
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
   `repository.insertion.*` -> `upload/upload-preview-file`). Sorting by *whose*
   disk splits it correctly:

   1. **synced-folder access** -- `:preview-images`, `:folders`, the iCloud
      directories. Every machine has these already (that is what made
      read-only replicas possible at all), so the sync distributes the result
      and *any* machine may do the work. Placement is then free, and the hub
      wins by default: it is where the accompanying db write happens, so doing
      it there keeps one user action to one round trip.
   2. **this-machine access** -- the OS `open` call, the Obsidian temp file, an
      HTTP multipart tempfile, bytes served to this browser. No sync can carry
      these; they are meaningless anywhere but the machine the human is at.

   Only (2) is machine-local. `machine-local-commands` is exactly the commands
   that need (2).")


(def machine-local-commands
  "The `/ui` commands that must run on the machine with the browser. Everything
   else in dispatch's list goes to the hub -- `api.placement-sweep-test` asserts
   the two sets together cover that list exactly, so a command added to
   `dispatch` cannot default silently into either half.

   All four entries are one workflow: the Obsidian round trip. The human's
   editor opens a temp file on *his* machine, he edits it there, and a later
   command reads it back. The file is the state, it is not in a synced folder,
   and `opener/open-in-obsidian` shells out to the local OS -- so the whole
   sequence has to stay on one machine, and that machine is his.

   - `edit-item-in-obsidian` writes the temp file and opens the editor;
   - `sync-obsidian-changes` reads it back -- and this one also *writes the
     description*, so it is the one command in this set that needs the hub too.
     It is local because of where the file is, and it calls the hub for the
     write (see `handoffs/RHIZOME_ARCH_REWORK_2.md`, the Obsidian protocol);
   - `discard-obsidian-changes` deletes the temp file;
   - `get-obsidian-file-content` reads it.

   Note what is deliberately NOT here, because each looks like a candidate:

   - `insert-item` -- the scrapers write preview images, but to the synced
     `:preview-images` folder. See the namespace docstring: synced is free.
   - `delete-item` / `delete-context` -- `repository.deletion` removes files
     beside the item, again in synced folders, and the deletion has to be
     atomic with the db rows. Splitting it would risk a half-deletion across a
     network.
   - `list-resources` -- the import branches scan the drop folder, which *is*
     machine-local. But import is not reached through `/ui` today (it runs from
     `/api` and the batch path), so nothing in the `/ui` classification turns
     on it. If an import command is ever added to `dispatch`, it belongs here."
  #{"edit-item-in-obsidian"
    "sync-obsidian-changes"
    "discard-obsidian-changes"
    "get-obsidian-file-content"})

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
  "The top-level HTTP routes of `server.clj`, each assigned a half. Three
   values, because two do not fit:

   - `:local` -- answered by this machine, never proxied;
   - `:hub`   -- proxied to the hub whole;
   - `:split` -- dispatched per call. `/ui` is the only one: by command name,
     see `machine-local-commands`.

   Keys are the route paths exactly as they appear in `server.clj`, so
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
  {"/ui"                 :split
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

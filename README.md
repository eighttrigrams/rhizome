# Rhizome

For the whitepaper, see here: [*Rhizome - A “total recall” note-taking and content-management and -archival system for Superhuman Memory [Whitepaper]*](https://eighttrigrams.net/article/21)

## Getting started - with Docker

Clone [us-vs-them](https://github.com/eighttrigrams/us-vs-them) into a sibling
directory first. `deps.edn` names it `{:local/root "../us-vs-them"}`, which
tools.deps resolves relative to that file, and the box mounts it read-only at
`/workspace/us-vs-them` to match. Without it the first `clj` invocation stops
at `Local lib eighttrigrams/us-vs-them not found`. What it is for: *Line
provenance*, below.

Then run

```bash
make box
root@dev-box:/workspace/rhizome# make onboard
```

That is the whole setup. A fresh clone plus that sibling is enough — nothing
per-machine to write first, no token, no local compose file. Add `WITH_VEC=1`
for semantic search (*With Vector DB*, below); both forms stand on their own:

```bash
make box
make box WITH_VEC=1
```

To start the app, use:

## Starting the App

```bash
root@dev-box:/workspace/rhizome# make start
```

When the app is up: press 'c', then type "ar" and hit Enter and you should be in context "Articles", where you should see a couple
of articles listed on the right hand side.

![header](./header.png)

```bash
make stop
make test # if you want to run the tests
make start # to start the server again
```

## Onboard and Cleanup

The command `make onboard` writes a fresh `config.edn`, and a `files` directory in which files imported into Rhizome will be stored. The command `make start` creates a db with demo contexts and articles. These work inside and outside the container. The db and the configs are shared from both sides.

To *remove* configs and db again, use `make clean`. If you want to not seed items, use `:skip-seed?` option.

## Getting started on the host system

Prerequisites are

- JDK21
- Clojure CLI (`clj`)
- Babashka (`bb`)
- node 18+, npm
- sqlite3 CLI
- imagemagick
- a checkout of [us-vs-them](https://github.com/eighttrigrams/us-vs-them) in a
  sibling directory (see above)

```bash
npm i
make onboard # If you haven't done already
make test
make start  
make stop
```

## With Vector DB

**In Docker:** just add `WITH_VEC=1`. An `ollama` sidecar container is
brought up automatically; the embedding model is pulled into a named volume
on first run and cached afterwards. No host-side Ollama install required.

That volume is `rhizome_ollama_models`, and it is deliberately one per
*machine* rather than one per checkout — it holds a downloaded model, so a
second clone should find it already there rather than pull ~640 MB again.
Everything else a box keeps is still namespaced per checkout.

```bash
make box WITH_VEC=1
```

**On the host system:** you need Ollama yourself. On MacOS:

```bash
brew install ollama # or your platform's installer
ollama pull qwen3-embedding:0.6b
ollama serve        # listens on http://127.0.0.1:11434
make install-sqlite-vec
```

In both cases, if you haven't yet onboarded, do so

```bash
make onboard
```

or simply add

```clojure
:semsearch {:vec-path #or [#env VEC_PATH "./.sqlite-vec/vec0"]
            :ollama-url #or [#env VEC_URL "http://127.0.0.1:11434"]
            :ollama-model "qwen3-embedding:0.6b"}
```

by hand to `config.edn`. The aero `#or [#env ...]` form lets the same `config.edn` work on host and inside docker: the Dockerfile sets `VEC_PATH=/usr/local/lib/sqlite-vec/vec0` and `VEC_URL=http://127.0.0.1:11437` (a socat bridge `entrypoint.sh` opens onto the `ollama` sidecar); the host falls back to the local install path and `:11434`.

After installing vec, embed the seeded demo articles, while Rhizome is running, run:

```bash
nohup make start & # invoked that way that you can execute next line from the same shell
make backfill-embeddings
```

### Usage

- Visit the Articles context
- Press 'i' (input field on right hand side opens)
- Press 'shift+option+v' (input field should become green)
- Type in a search term

#### Tests

When `:semsearch` is configured in `config.edn` (with a `:vec-path` pointing at a dylib that exists on disk), some additional unit tests run. To skip them, remove the `:semsearch` block from `config.edn` — there is no env-var override anymore.

## End-to-end (Playwright)

E2E tests share the dev port from `config.edn` (3140 by default; override by exporting `PORT=…` in your shell — via direnv, a manual `export`, or inline `PORT=… make e2e`). The lockfile (`.dev-server.lock`) makes dev and e2e mutually exclusive — start one and the other refuses with a diagnostic that includes mode, env, and (for e2e) headed.

```bash
$ npx playwright install chromium                # first time only (on host system only)
$ make e2e                                       # full headless run
$ make e2e HEADED=1                              # show the browser window
$ make e2e T="creates a context"                 # filter by scenario (playwright -g)
$ make e2e NO_BUILD=1                            # skip shadow-cljs release build
                                                 # (reuses cached main.js — fine when
                                                 # no cljs changed since last run)
$ make e2e NO_BUILD=1 T="creates a context"      # iterate fast on one scenario
```

Both work on the host system and in the Docker containers.

## Package, deploy and run

Define these functions

```bash
rhizome-start() {
    if lsof -nP -iTCP:3007 -sTCP:LISTEN >/dev/null 2>&1; then
      echo ":3007 already in use — run rhizome-stop first"
      return 1
    fi
    (cd ~/Applications/rhizome && java -cp server.jar clojure.main -m server &)
    ~/Applications/Tracker-darwin-arm64/Tracker.app/Contents/MacOS/Tracker 2>/dev/null
}
rhizome-stop() {
    local pids=$(lsof -nP -iTCP:3007 -sTCP:LISTEN -t)
    [ -n "$pids" ] && kill $pids || echo "nothing on :3007"
}
```

and then use

```bash
make deploy DEPLOY_TARGET=~/Applications/rhizome
rhizome-start
rhizome-stop
```

`DEPLOY_TARGET` is required and must be passed on the command line — there is
no default and it is deliberately not read from the environment.

Comments
- We use nativefier to serve the app via electron
- A first time run will seed some necessary contexts

## Primary and replica

The rhizome directory is synced between machines, and only one copy may write to
the db. Files ending in `.nosync` are excluded from that sync, so a marker named
`primary.nosync` — in the directory the app starts from, next to `config.edn` —
exists on exactly one machine: the **primary**.

An instance that starts in prod mode (`:dev?` false) *without* that marker is a
**read-only replica**:

- its sqlite db is opened read-only, so no code path can write to it — forgotten
  ones included;
- writes are refused gracefully in front of that: `/api` mutations
  (recording-mode toggle and embeddings backfill included) and `/upload` answer
  `403 {"read-only-replica": true}`; `/ui` carries queries and mutations through
  one POST, so it refuses per command and in band — a normal `200` whose transit
  body is `{:read-only-refused "…" :cmd nil :arg nil}`, leaving the list on
  screen (the cleared `:cmd`/`:arg` keep the refused command from staying latched
  in the SPA state it is merged into) — and queries pass;
- the youtube/atom pollers are not scheduled at all;
- the UI carries a standing red badge, and `GET /api/status` reports the role.

Startup logs the role it booted with as one line (`INSTANCE ROLE: PRIMARY` /
`INSTANCE ROLE: READ-ONLY REPLICA`, with the reason). The role is decided once
and held for the life of the process, so promoting a replica means placing the
marker and restarting:

```bash
touch primary.nosync   # next to config.edn, then restart the app
```

Dev mode is unaffected — no marker needed, no guards, no badge.

## Part-of relations and hierarchy mode

A relation says two items belong together; it does not say how. Over that flat
web lies a second, sparse layer: a relation may additionally be marked
**part-of**, meaning its owner is the whole and its target one of the parts — a
chapter *of* a book, rather than a note *about* it. Two columns on `relations`
carry it, `is_part_of` and `part_of_sort_idx`, the latter being the position
among the siblings under one whole.

That index is independent of `items.sort_idx`, because it belongs to the edge
and not to the item: a part may sit under several wholes and take a different
position under each.

Both are edited per relation line in the item edit modal (`e`). The index field
takes a plain integer, and only that — the roman numerals the "Sort index" field
on the left of the same modal accepts are `items.sort_idx`'s convention, not this
one's — and left empty it means the part has no place yet. Over the REST API they
are `is-part-of` and `part-of-sort-idx` on `PUT /api/relations`.

The part-of edges form a **directed acyclic graph**, not a tree. Several wholes
over one part is expected; a cycle is not. A write that would close a loop is
refused in the backend — below both `/ui` and `/api`, since the guarantee is
about the database and not about one client — and the refusal names the path:

```
Refused: this would make a thing part of itself — Chapter (13) → Book (12) → Chapter (13)
```

`/api` answers `409` (with the path as ids in `part-of-cycle`); the SPA shows
the message in the edit modal, where the edit was made. Plain relations stay
unconstrained and may go on forming cycles.

**Hierarchy mode** (`shift+option+h`) shows that layer on its own. A strip
appears at the top of the page — taking its own row, so the app below it is
shorter by exactly its height rather than being covered by it — and with a
context selected:

- the item list is that context's parts: the ones carrying a
  `part_of_sort_idx` first, ascending, then the ones left unset — a part nobody
  placed does not push ahead of the parts somebody did, and it is still listed;
- items merely related to the context are not listed at all — that exclusion is
  the point of the mode;
- the intersection and filtering section disappears, since none of it means
  anything in a hierarchy.

### Levels

That list is **level 1**. The strip carries a stepper — `‹ 2 ›`, clickable, no
key bound to it — that reads the levels below it. Level 2 is the parts of those
parts, level N the nodes at depth exactly N: a level lists what sits at that
depth and nothing else, so the direct children are not among the level-2 rows.

The edges are a DAG, but seen from the selected context they unroll into a
tree, and that tree is what the levels index. Two things follow:

- **Order comes from the whole path**, not from a node's own sibling index. The
  tuple of `part_of_sort_idx` from the selected context down to the node is
  compared component by component, so everything under the first child comes
  before everything under the second whatever indices are used further down.
  The unset `-1` sorts behind the placed ones at *every* component of that
  path, not only at the last — a chapter nobody placed carries its pages to the
  back with it.
- **A node appears once per path.** The same item filed under two chapters of
  the same book is listed at both places it occupies, not once. A level is as
  long as there are paths into it, which in a DAG can grow with depth without
  any cycle being involved.

The stepper is bounded on the fly, at both ends: a step that would land on a
level with nothing at it is not offered rather than offered and then answered
with an empty list. Filtering counts — typing in the item search filters the
hierarchy list like any other, so the bound is counted against the same search
and the levels the filter empties stop being offered while it stands.

The mode is session state, like danger mode: not persisted, not per-context.
The level is the same kind of thing, and it belongs to the context it was
counted under — select another one and it is level 1 again, because level 2 of
one context names other things than level 2 of the next.

Selecting a child does not re-root the view on it — that is still to come.

## Line provenance

Descriptions are written from more than one direction: typed into the app,
synced back from Obsidian, pulled in by a scraper, and — increasingly —
rewritten by agents over the REST API. Every version already records which of
those it came from, and the version bar over a description has always shown it.
That answers a question about a *version*, and it is not the question that
matters when something is about to be edited.

**Provenance answers the other one: of the text as it reads right now, which
lines are whose.** An item written once by hand and edited nineteen times since
by an agent still has its opening paragraph attributed by hand, and a list of
nineteen agent versions would never say so.

The button for it sits on the right of the version bar, under *this item*,
opposite the arrows and Diff, which are under *this version*. The split is the
point: Diff compares two versions of a text, provenance attributes the text that
is standing — whichever version the arrows happen to be pointing at.

The page shows the description's **source text**, line-numbered, each line
washed with a colour and carrying a solid bar in the gutter. Rendered markdown
would not do: the answer indexes source lines, and a paragraph is many source
lines inside one `<p>`, so a paragraph half hand-written and half an agent's
would have to be painted one colour — and would then be saying something false
about the writing it covers.

The number at the head of each range is a **caution**, 1.00 down to 0.00:

- **1.00** — written wholly by hand, from the web UI (`app`) or synced back from
  the editor (`obsidian`). Not an agent's to rewrite.
- **0.00** — written wholly through the REST API (`api`) or by a scraper
  (`scraper`). Free to edit.
- **In between** — both have worked on that stretch, and the number is the share
  of its lines that are hand-written. So 0.00 is the *only* value meaning "not
  one line of his in here"; 0.39 is not "mostly the agent's", it is a stretch
  that still contains his lines.

A stretch is measured as an island rather than line by line, which is what keeps
an agent's sentence dropped into the middle of a paragraph from cutting it in
three: it dilutes the paragraph instead, because it cannot be edited without
touching the work around it. Absorption runs one way only — a hand-written line
landing inside an agent's block stays hand-written.

None of that arithmetic lives here. It is
[us-vs-them](https://github.com/eighttrigrams/us-vs-them), a sibling checkout
(see *Getting started*); `src/clj/provenance.clj` is the whole of rhizome's part
in it, and says which source markers count as whose and nothing else.

**Agents get the same answer without the page.** `GET /api/items/:id` carries
`caution` — ranges one-based and inclusive over the description, and a legend
that explains the scale, so the number arrives with instructions for reading it
rather than as a bare float:

```json
"caution": {
  "legend": "caution runs from 1.00 to 0.00 over the lines of the text it is served with. …",
  "ranges": [{"from": 1, "to": 12, "caution": 1.0},
             {"from": 13, "to": 20, "caution": 0.0}]
}
```

Two things a caller should know. A description nobody has edited since it was
written comes back as one range covering all of it — the honest answer, not a
missing one. And a body ending in a newline is one line longer than a split that
discards the trailing empty field: line up with these numbers by splitting on
`\n` and keeping it.

### The text a relation carries has the same history

A relation holds a body of text of its own — why *this* chapter is in *this*
book — edited in the modal a card's annotation strip opens. That text is
versioned by the mechanism above, and the same two questions can be asked of it:
which versions there were, and who wrote the one standing now.

Both are answered **inside that modal**, on a bar over the text that is the
item's version bar in miniature: arrows and the version label under *this
version*, a Provenance button under *this relation*. Not on a page of its own,
unlike the item's — the modal layer sits over the main one, so a page opened
from inside a modal would be drawn behind it.

Stepping back shows an older version **rendered and read-only**; there is no
editor on screen at all, and a save from there writes the text that is standing
rather than the one being read. What has been typed into the editor survives the
trip: it is read out before the editor is unmounted and put back when the bar
returns to the current version.

Two differences from an item's history, both deliberate:

- **The history is keyed on the two items, not on `relations.id`.** Saving either
  item's edit modal deletes that item's relation rows and re-inserts them, so the
  row's id is not the edge's identity. What does not change is which two items the
  edge runs between, and `relation_history` is keyed on that pair.
- **A save that did not change the text earns no version.** The description modal
  exists to write a description, so every save of it is a version even when the
  text came back identical; the relation modal writes the text alongside a badge,
  a part-of tick and two annotations, so a save there is not evidence that
  anything was written. Unticking a badge four times is not four versions.

An unlink is the one write that can destroy a relation's text with nobody having
typed over it — the row goes and the text goes with it — so the text is written to
the history on the way out, and marked. See the next section.

Only the web UI writes this text today, so every version of it reads as
hand-written; there is no `/api` route that replaces a relation's description the
way `PUT /api/items/:id` replaces an item's.

### Deletion is a tombstoning

Deleting used to keep the versions and take the text. The history an item had
accumulated stayed behind, the description it was actually carrying went with the
row, and nothing in the table said the item had been deleted rather than left
alone since its last edit — so the one version certain to be missing from a dead
item's history was its last one.

Now a delete **preserves before it scraps**. The standing text goes to the
history under one more version number, stamped with the source that wrote it, and
that row is marked `tombstone`. What is left under a dead id is a history whose
newest version was superseded by nothing, which is what makes it readable as a
deletion. It holds for both texts and for every way of deleting: the item's own
description, the text on every edge that pointed at it, a single delete, an
unlink, and the danger-mode bulk delete — which is the one gesture that can take
an edge's *owner*, so it tombstones edges in both directions.

The mark is written **whether or not there was anything to preserve**. An item
with an empty description was still an item, and an edge nobody wrote on was
still an edge; the event is what is being recorded, and a table a thing is simply
missing from cannot say whether it was ever there.

For an item that is archaeology — ids are never handed out twice, so a deleted
item's history is not reachable from the UI at all. For a relation it is not
archaeology, because **an edge can come back**. The history is keyed on the two
items, so unlinking and linking again leaves one version list with the cut marked
in the middle of it: the edge that returns opens blank, and a step back on its
version bar reads `Version 1 · app · unlinked` over the text it went out on. That
is the difference the mark buys — without it, the same list would read as a text
that had been blanked rather than an edge severed and made again.

Nothing reaps these rows, and nothing undeletes from them yet. What the
tombstones give today is that a deletion is recorded rather than silent, and that
the text a delete carried off is still there to read.

## Configuration Options

| Key | Notes |
|---|---|
| `:port` | HTTP port. Numeric, accepts `#env`/`#or` readers. |
| `:dev?` | Dev mode: REST API always open, `/test/reset` enabled, dev resource pipeline, hardcodes all `:folders` paths (under `./files/`) and the sqlite db path. Also auto-seeds the dev db on first start (canonical contexts + demo articles) when items are empty — set `:skip-seed? true` to opt out. |
| `:skip-seed?` | Skip the first-start auto-seed in dev mode. Useful when you want an empty dev db, or when you're restoring contexts/items from elsewhere. Ignored outside `:dev? true`. |
| `:db-path` | Sqlite file path (string). Required in prod. Must not be set when `:dev? true`. |
| `:folders` | Map of the media directories. Every key is **required in prod** — the app refuses to start if any is unset or its directory does not exist — and **must not be set when `:dev? true`** (all hardcoded under `./files/`). There is no shared root; each is an independent absolute path, so no symlinks are needed. Keys: `:imports` — the drop folder the import flow scans (dev: `./files/Downloads/Tracked/`); `:audio`, `:video`, `:docs`, `:images` — import destinations files are moved into, classified by suffix (dev: `Music/`, `Movies/`, `Documents/`, `Pictures/` under `…/Tracked/`); `:images` also backs `/imgs/*` and `/img-by-id`; `:preview-images` — previews written by the upload drag-and-drop fields, served at `/imgs/Preview/*` with downscaled variants under its `Lowres/` subfolder at `/imgs/Preview/Lowres/*` (dev: `./files/Pictures/Tracked/Preview/`). |
| `:semsearch` `:vec-path`, `:ollama-url`, `:ollama-model` | Single switch for semantic search. Present → app loads the sqlite-vec extension from `:vec-path` (no `.dylib`/`.so` suffix) and embeds against the Ollama endpoint. Absent → vec extension is not loaded, embedder is inert, and the `:vector` test selector is skipped. |
| `:substack` `:external-substacks` | List of external Substack hostnames (regex-matched on titles). |
| `:private-addr`, `:private-user-agent` | Prod-only allowlist: `/ui` is reachable only from this remote-addr + user-agent. Not used when `:dev? true`. |

## Running multiple checkouts side-by-side

Clone to a sibling directory and run a second instance — state is isolated automatically:

- Ports: export `PORT=...` / `SHADOW_PORT=...` in your shell before running `make` (drop an `.envrc` for direnv, or `export` them manually, or prefix the make invocation). The Makefile flows the exported values into both host-side `make start` and the docker overlay. When nothing is exported, ports come from `config.edn` / `shadow-cljs.edn` defaults — note that an `.envrc` alone is not consulted, it needs to actually be loaded into the env.
- Docker volumes/containers: `COMPOSE_PROJECT_NAME` is derived from the directory basename so they don't share volumes.

Caveats: renaming the checkout after first build orphans the old volumes (`docker volume rename` to migrate); the Ollama sidecar's `ollama_models` volume is per-project, so each checkout re-pulls `qwen3-embedding:0.6b` (~639 MB) on first `WITH_VEC=1` run.

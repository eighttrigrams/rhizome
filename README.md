# Rhizome

For the whitepaper, see here: [*Rhizome - A “total recall” note-taking and content-management and -archival system for Superhuman Memory [Whitepaper]*](https://eighttrigrams.net/article/21)

## Getting started - with Docker

Clone [us-vs-them](https://github.com/eighttrigrams/us-vs-them) into a sibling
directory first. `deps.edn` names it `{:local/root "../us-vs-them"}`, which
tools.deps resolves relative to that file, and the box mounts it read-only at
`/workspace/us-vs-them` to match. Without it the first `clj` invocation stops
at `Local lib eighttrigrams/us-vs-them not found`. What it is for: *Line
provenance*, below.

Run

```bash
make box
root@dev-box:/workspace/rhizome# make onboard
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

## Docker - Claude YOLO

Sandboxed Claude (using Docker). Run

```bash
make yolo
claude@yolo-box:/workspace/rhizome$ make onboard # if haven't done already
claude@yolo-box:/workspace/rhizome$ claude # has playwright MCP, can start app etc.
```

Its egress is locked. The box sits on an internal network with no default
gateway, and the only route out is a tinyproxy sidecar forwarding to the hosts
listed in `docker/tinyproxy.filter` — `api.anthropic.com` and nothing else. Its
dependencies are baked into the image at build time, so no run needs Clojars,
Maven Central or the npm registry. See `docker/README.md` to allow another host.

The escape hatch, when a run genuinely needs the network (bumping dependencies,
say):

```bash
make yolo INTERNET=1
```

`make box` is unaffected either way — it is the plain root dev shell, not an
agent surface, and stays on the default bridge.

## With Vector DB

**In Docker:** just add `WITH_VEC=1`. An `ollama` sidecar container is
brought up automatically; the embedding model is pulled into a named volume
on first run and cached afterwards. No host-side Ollama install required.

```bash
make box WITH_VEC=1
make yolo WITH_VEC=1
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
  "legend": "caution runs from 1.00 to 0.00 over the lines of this item's description. …",
  "ranges": [{"from": 1, "to": 12, "caution": 1.0},
             {"from": 13, "to": 20, "caution": 0.0}]
}
```

Two things a caller should know. A description nobody has edited since it was
written comes back as one range covering all of it — the honest answer, not a
missing one. And a body ending in a newline is one line longer than a split that
discards the trailing empty field: line up with these numbers by splitting on
`\n` and keeping it.

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

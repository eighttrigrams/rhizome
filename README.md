# Rhizome

For the whitepaper, see here: [*Rhizome - A “total recall” note-taking and content-management and -archival system for Superhuman Memory [Whitepaper]*](https://eighttrigrams.net/article/21)

## Getting started - with Docker

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

The command 

```bash
make onboard
```

- writes a fresh `config.edn` (with the chosen or default ports; and a `docker/.env` file, with ports information)
- creates a db with demo contexts and articles. It works inside and outside the container, and the db and the configs are shared from both sides. 
- creates the `files` directory in which files imported into Rhizome will be stored

To *remove* configs and db again, use 

```bash
make clean
```

## Getting started on the host system

Prerequisites are

- JDK21
- Clojure CLI (`clj`)
- Babashka (`bb`)
- node 18+, npm
- sqlite3 CLI

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
ollama pull nomic-embed-text
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
            :ollama-model "nomic-embed-text"}
```

by hand to `config.edn`. The aero `#or [#env ...]` form lets the same `config.edn` work on host and inside docker: the Dockerfile sets `VEC_PATH=/usr/local/lib/sqlite-vec/vec0` and `VEC_URL=http://127.0.0.1:11437` (a socat bridge `entrypoint.sh` opens onto the `ollama` sidecar); the host falls back to the local install path and `:11434`.

After installing vec, embed the seeded demo articles, while Rhizome is running, run:

```bash
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

E2E tests share the dev port from `config.edn` (3006 by default; override via `.envrc` or `PORT=…`). The lockfile (`.dev-server.lock`) makes dev and e2e mutually exclusive — start one and the other refuses with a diagnostic that includes mode, env, and (for e2e) headed.

```bash
$ npx playwright install chromium  # first time only (on host system only)
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
make deploy
rhizome-start
rhizome-stop
```

## Configuration Options

The server reads its config from `./config.edn`. Example (dev):

```clojure
{:port #long #or [#env PORT 3006]
 :dev? true
 :semsearch {:vec-path #or [#env VEC_PATH "./.sqlite-vec/vec0"]
             :ollama-url #or [#env VEC_URL "http://127.0.0.1:11434"]
             :ollama-model "nomic-embed-text"}}
```

### Top-level keys

| Key | Notes |
|---|---|
| `:port` | HTTP port. Numeric, accepts `#env`/`#or` readers. |
| `:dev?` | Dev mode: REST API always open, `/test/reset` enabled, dev resource pipeline, hardcodes `:folders/:homefolder` and the sqlite db path. Also auto-seeds the dev db on first start (canonical contexts + demo articles) when items are empty — set `:skip-seed? true` to opt out. |
| `:skip-seed?` | Skip the first-start auto-seed in dev mode. Useful when you want an empty dev db, or when you're restoring contexts/items from elsewhere. Ignored outside `:dev? true`. |
| `:db-path` | Sqlite file path (string). Required in prod. **Must not be set when `:dev? true`** — dev/test/e2e modes hardcode their own paths (see below). |
| `:folders` `:homefolder` | Filesystem root for user files. Required in prod, **must not be set when `:dev? true`** (hardcoded to `./files/`). |
| `:semsearch` `:vec-path`, `:ollama-url`, `:ollama-model` | Single switch for semantic search. Present → app loads the sqlite-vec extension from `:vec-path` (no `.dylib`/`.so` suffix) and embeds against the Ollama endpoint. Absent → vec extension is not loaded, embedder is inert, and the `:vector` test selector is skipped. |
| `:substack` `:external-substacks` | List of external Substack hostnames (regex-matched on titles). |
| `:private-addr`, `:private-user-agent` | Prod-only allowlist: `/api` is reachable only from this remote-addr + user-agent. Not used when `:dev? true`. |

### `:db-path`

Outside dev mode, set `:db-path "..."` to point at the sqlite file. In dev/test/e2e modes the path is hardcoded: `./rhizome.db` (dev), shared-cache in-memory (test), `./test/rhizome-e2e.db` (e2e).

### Auto-seed in dev mode

When `:dev? true` and the items table is empty (i.e. you just ran `make clean` or this is a fresh checkout), the JVM seeds the dev db on startup with the canonical contexts and the demo articles (`scripts/demo-articles.edn`). No more "did I run `make onboard`?" — `make start` is enough.

Set `:skip-seed? true` in `config.edn` to opt out (e.g. if you're restoring data from a backup, or want to drive the empty db yourself):

```clojure
{:port #long #or [#env PORT 3006]
 :dev? true
 :skip-seed? true
 ...}
```

`:skip-seed?` is a no-op outside dev mode. Seeding never re-runs after the first start, since the trigger is "items table is empty" — once you have any items, dev-seed skips itself.

### Running multiple checkouts side-by-side

You can clone or copy this repo to a sibling directory and run a second instance against different ports without touching shared state — both the host-side dev server and the docker containers are isolated automatically.

Per-checkout isolation already in place:

- `.dev-server.lock`, `rhizome.db`, `files/`, `config.edn`, and the generated `docker/compose.ports.yml` all live inside the repo, so each copy has its own.
- Ports: drop an `.envrc` at the repo root with `export PORT=...` and/or `export SHADOW_PORT=...`. `scripts/detect-ports.sh` picks it up, and the Makefile flows the values into both host-side `make start` and the docker overlay. Without `.envrc`, ports come from `config.edn` / `shadow-cljs.edn`.
- Docker volumes and container names: the Makefile derives `COMPOSE_PROJECT_NAME` from this checkout's directory basename (lowercased; `.` → `-`). Two clones called `rhizome` and `rhizome.alt` get `rhizome_*` and `rhizome-alt_*` volume prefixes, so the m2 / npm / shadow-cljs / cpcache / node_modules / workspace volumes don't collide.

A typical second-checkout setup:

```bash
git clone <repo> ../rhizome.alt
cd ../rhizome.alt
cat > .envrc <<'EOF'
export PORT=3007
export SHADOW_PORT=9805
EOF
make onboard
make start            # host-side, on :3007 / :9805
# or, with full docker isolation:
make box              # COMPOSE_PROJECT_NAME=rhizome-alt
```

Override the project name explicitly if you want to pin it independently of the directory name:

```bash
make box COMPOSE_PROJECT_NAME=my-rhizome-prototype
```

Caveats:

- Renaming the checkout directory after first build changes `COMPOSE_PROJECT_NAME`, which makes docker see fresh empty volumes. To preserve the existing volumes, pass the old name explicitly with `COMPOSE_PROJECT_NAME=...` until you migrate.
- The Ollama sidecar's model volume (`ollama_models`) is also per-project, so each checkout re-pulls `nomic-embed-text` (~3 GB) on first `WITH_VEC=1` run. Acceptable for occasional second-instance work; if you need to share, pin the same `COMPOSE_PROJECT_NAME` across checkouts.

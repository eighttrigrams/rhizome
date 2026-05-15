# Rhizome

For the whitepaper, see here: [*Rhizome - A “total recall” note-taking and content-management and -archival system for Superhuman Memory [Whitepaper]*](https://eighttrigrams.net/article/21)

## Getting started - with Docker

Run

```bash
make box [PORT=3006] [SHADOW_PORT=8020] [SHADOW_NREPL_PORT=9630]
root@dev-box:/workspace/rhizome# make onboard
```

To start the app, use:

## Starting the App

```bash
root@dev-box:/workspace/rhizome# make start
```

Not that the command comes back immediately, but that the build can take some time, so have a little patience
(until the app comes up (~30-45s) and the stale build shadow cljs banner disappears (~45-75s).

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
:semsearch {:ollama-url "http://127.0.0.1:11434"
            :ollama-model "nomic-embed-text"}
```

by hand to `config.edn`.

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

When vector mode is enabled, some additional unit tests run,
but can also be skipped with

```bash
SQLITE_VEC_PATH=/nope make test
```

## End-to-end (Playwright)

E2E tests run at port 3005.

```bash
$ npx playwright install chromium  # first time only (on host system only)
$ make e2e                  # headless (default)
$ make e2e HEADED=1         # show the browser window
$ make e2e E2E_PORT=3015    # override the port
```

This works on the host system as well as in the Docker containers.

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

The server reads its config from `./config.edn` (override with the
`RHIZOME_CONFIG` env var). Example (dev):

```clojure
{:port 3006
 :dev? true
 :db {}
 :semsearch {:ollama-url "http://127.0.0.1:11434"
             :ollama-model "nomic-embed-text"}}
```

### Top-level keys

| Key | Notes |
|---|---|
| `:port` | HTTP port. Numeric, accepts `#env`/`#or` readers. |
| `:bind-host` | Optional explicit bind address. |
| `:dev?` | Dev mode: REST API always open, `/test/reset` enabled, dev resource pipeline, hardcodes `:folders/:homefolder` and `:db/:dbname`. |
| `:test?` | Dev sub-mode for unit tests. Requires `:dev? true`. Mutually exclusive with `:e2e?`. Hardcodes db to `./test/rhizome-test.db`. |
| `:e2e?` | Dev sub-mode for Playwright e2e. Requires `:dev? true`. Mutually exclusive with `:test?`. Hardcodes db to `./test/rhizome-e2e.db`. |
| `:db` | SQLite config (see below). |
| `:folders` `:homefolder` | Filesystem root for user files. Required in prod, **must not be set when `:dev? true`** (hardcoded to `./files/`). |
| `:semsearch` `:ollama-url`, `:ollama-model` | Ollama endpoint and model for embeddings. |
| `:substack` `:external-substacks` | List of external Substack hostnames (regex-matched on titles). |
| `:private-addr`, `:private-user-agent` | Prod-only allowlist: `/api` is reachable only from this remote-addr + user-agent. Not used when `:dev? true`. |

### `:db`

Only SQLite is supported. In `:dev?` mode `:dbname` is hardcoded (so leave `:db` as `{}`); the path depends on the sub-mode:

- bare dev → `./rhizome.db`
- `:test? true` → `./test/rhizome-test.db`
- `:e2e? true` → `./test/rhizome-e2e.db`

Outside dev mode, set `:db {:dbname "..."}` explicitly.

### Env vars

| Var | Effect |
|---|---|
| `RHIZOME_CONFIG` | Config file path. Default `./config.edn`. |
| `RHIZOME_BIND_ALL=1` | In dev only, bind to `0.0.0.0` instead of `127.0.0.1`. Useful for LAN access. |
| `SQLITE_VEC_PATH` | Override the sqlite-vec extension lookup (path without `.dylib`/`.so`). Used for vector search; if missing, vector features are unavailable but the rest works. |

EDN readers `#env [NAME default]` and `#or [a b ...]` are available in the config file (used in `test/e2e_config.edn` for the port).

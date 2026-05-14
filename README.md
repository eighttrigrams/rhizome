# Rhizome

For the whitepaper, see here: [*Rhizome - A “total recall” note-taking and content-management and -archival system for Superhuman Memory [Whitepaper]*](https://eighttrigrams.net/article/21)

## Getting started - with Docker

Run

```bash
make box [PORT=3006] [SHADOW_PORT=8020] [SHADOW_NREPL_PORT=9630]
root@dev-box:/workspace/rhizome# make onboard
```

The command `make onboard` writes a fresh `config.edn` (with the chosen or default ports), applies the schema to
`rhizome.db` / `rhizome-test.db`, and seeds the demo contexts and articles.

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

```
make stop
make test # if you want to run the tests
make start # to start the server again
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
make onboard # If you haven't done this already
make start      
```

If you've already onboarded on the other side (host vs. container), skip
`make onboard` — `rhizome.db` and `config.edn` are already there from the
bind-mount (you still need `npm install` once on this side because each
side has its own `node_modules`).

There exists `make clean`, if something needs to be cleaned up. Switching 
between working on the host system and inside Docker should work, but if there
are problems, this command helps with removing the test dbs and the test configs.

Then start the app (see above)

```bash
make start  
```

## Docker - Claude YOLO

Sandboxed Claude (using Docker). Run

```bash
make yolo
claude@yolo-box:/workspace/rhizome$ make onboard # if haven't done already
claude@yolo-box:/workspace/rhizome$ claude # has playwright MCP, can start app etc.
```

### With Vector DB

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

#### Usage

- Visit the Articles context
- Press 'i' (input field on right hand side opens)
- Press 'shift+option+v' (input field should become green)
- Type in a search term

##### Tests

When vector mode is enabled, some additional unit tests run,
but can also be skipped with

```bash
SQLITE_VEC_PATH=/nope make test
```

## End-to-end (Playwright)

E2E tests run at port 3005.

```bash
$ npx playwright install chromium  # first time only (on host system only)
$ make e2e               # headless (default)
$ make e2e HEADED=1      # show the browser window
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

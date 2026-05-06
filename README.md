# Rhizome

For the whitepaper, see here: [*Rhizome - A “total recall” note-taking and content-management and -archival system for Superhuman Memory [Whitepaper]*](https://eighttrigrams.net/article/21)

## Getting started

```bash
make onbaord
```

Visit `localhost:3006`

```
make stop
make test # if you want to run the tests
make start
```

### With vector db

Clean with `git clean -xfd`.

```bash
make install-sqlite-vec
make onbaord
```

Visit `localhost:3006`

Add items with a description to a context of your choice.

TODO explain how

Enable recording mode and call

```
make backfill-embeddings
```

TODO explain how to find something

### End-to-end (Playwright)

Headless browser tests live under `test/e2e/`. They drive the real UI against a
server bound to a separate port (`:3005`) using `./rhizome-e2e.db`, with
state reset between scenarios via `POST /test/reset`.

```bash
$ npm install
$ npx playwright install chromium   # first time only
$ npm run e2e
```

Each run builds a fresh production-mode cljs bundle (`shadow-cljs release
app`) before booting the JVM, so the artifact under test has no shadow
devtools client embedded — it's the same shape of bundle that ships in
`./deploy.sh`. The webServer command is therefore
`npx shadow-cljs release app && RHIZOME_CONFIG=./test/e2e_config.edn clj -M -m server`.

The `RHIZOME_CONFIG` env var overrides the default `./config.edn` path —
useful for any alternate profile, not just e2e.

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

# Rhizome

For the whitepaper, see here: [*Rhizome - A “total recall” note-taking and content-management and -archival system for Superhuman Memory [Whitepaper]*](https://eighttrigrams.net/article/21)

## Getting started

```bash
make onboard
```

Visit `localhost:3006` (you might want to give it some seconds, then refresh until you see items listed).
Press 'c', then type "ar" and hit Enter and you should be in context "Articles", where you should see a couple
of articles listed on the right hand side.

```
make stop
make test # if you want to run the tests
make start # to start the server again
```

### With Vector DB

Start with a fresh clone of this git repo (or `git clean -xfd` an existing
checkout to wipe build artefacts, ignored files, and local databases).

```bash
brew install ollama # or your platform's installer
ollama pull nomic-embed-text
ollama serve        # listens on http://127.0.0.1:11434
make install-sqlite-vec
make onboard
```

- Visit the Articles context
- Press 'i' (input field on right hand side opens)
- Press 'shift+option+v' (input field should become green)
- Type in a search term

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

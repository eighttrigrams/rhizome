# Rhizome

For the whitepaper, see here: [*Rhizome - A "total recall" note-taking and content-management and -archival system for Superhuman Memory [Whitepaper]*](https://eighttrigrams.substack.com/p/superhuman-memory)

## Getting started

```bash
$ ln -s </absolute-path-to-your-git-workspace>/tracker/files/Pictures/Tracked resources/public/imgs
$ npm i
$ cp config.edn.template config.edn # Edit! Make sure that :folders :homefolder points to /<.../your-git-workspace>/tracker/files/
$1 make start                # Server
$2 npx shadow-cljs watch app # Frontend
```

Visit `localhost:8020`

> Storage is **SQLite** (single file, no server). If you're upgrading from a
> Postgres-backed checkout, see [MIGRATION_GUIDE.md](./MIGRATION_GUIDE.md) for
> the one-shot `clj -M:migrate` data import.

## Tests

Unit + API tests (Clojure, against a local SQLite file at `./rhizome-test.db`,
schema applied automatically on test load):

```bash
$ clj -X:test       # or `make test`
```

The `test/api/` suite uses `ring.mock` + the same JSON+transit envelope the
frontend sends, so it covers the wire format end-to-end. The harness in
`test/api/harness.clj` owns serialization — test bodies stay agnostic of it.

### End-to-end (Playwright)

Headless browser tests live under `e2e/`. They drive the real UI against a
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
`npx shadow-cljs release app && RHIZOME_CONFIG=./e2e_config.edn clj -M -m server`.

The `RHIZOME_CONFIG` env var overrides the default `./config.edn` path —
useful for any alternate profile, not just e2e.

## REPL Workflow (Server)

Instead of starting the server with `make start`, begin with
firing up a REPL, either by jacking-in or by running `clj -M:dev`. 
Then execute the following:

```clojure
clj:user:> (start)
{:started ["#'resources/resources" "#'server/http-server"]}
```

### VSCode

- Jack-in
    - deps.edn
        - Profile: :dev
- Jack-in
    - shadow-cljs
        - :app
            - :app

## Package and run

```bash
$ ./deploy.sh
$ ./start.sh
visit localhost:3000
```

## Clean

```bash
$ rm -rf resources/public/js/*
```

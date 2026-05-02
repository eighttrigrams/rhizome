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

## Vector / semantic search

Item search has an opt-in semantic mode: while the items input is open,
press **Shift+Option+V** to toggle it. The input gets a green outline,
sort modes are bypassed, and results are ranked by cosine similarity
against `items.embedding`. Secondary-context filters still apply.

The same path is also exposed at the REST API as
`GET /rest/items/:id/related?vector=true&q=…` (see `src/clj/rest_api.clj`).

### Runtime requirements

- **sqlite-vec** — the `vec0` SQLite extension is loaded on every JDBC
  connection (`src/clj/datastore/connection.clj`). On Mac the loader
  defaults to `./.sqlite-vec/vec0`; install it once with
  `bin/install-sqlite-vec.sh`. On Linux it defaults to
  `/usr/local/lib/sqlite-vec/vec0`. `SQLITE_VEC_PATH` overrides both.

- **Ollama** — query embedding is done by Ollama, default
  `http://127.0.0.1:11434`, model `nomic-embed-text` (768-dim). Both are
  configurable via `OLLAMA_URL` and `OLLAMA_EMBED_MODEL`. Items are
  embedded the same way at insertion time, so query and corpus must use
  the *same* model — switching it invalidates existing embeddings.
  `ollama pull nomic-embed-text` and `ollama serve` on the host.

- **Embedding coverage** — only items with a non-empty description are
  embedded; title-only items never appear in vector results. After bulk
  ingest or a model switch run `POST /rest/backfill/embeddings`
  (idempotent, resumable, gated by recording mode).

### Running through Docker

`docker-rhizome/` ships a dev container that bundles all of the above
except Ollama itself (which stays on the host):

- **Base image is `clojure:temurin-21-tools-deps-bookworm-slim`, not
  Alpine.** sqlite-vec's prebuilt linux loadable is built against glibc
  with FORTIFY_SOURCE; Alpine + `gcompat` doesn't provide
  `__memcpy_chk`, so `vec0.so` fails to relocate at load time. Debian
  slim resolves all symbols against the system glibc.

- **sqlite-vec 0.1.9, not 0.1.6.** The 0.1.6 release ships a mislabelled
  `linux-aarch64` asset (it's actually a 32-bit ARM build) and fails on
  aarch64 hosts with "Exec format error". 0.1.9 ships a real 64-bit
  aarch64 binary.

- **The Dockerfile picks the right slug from `uname -m`** so the same
  image definition builds correctly on x86_64 *and* aarch64 (Apple
  Silicon) hosts.

- **Ollama runs on the host, not in the container.** The compose file
  sets `OLLAMA_URL=http://host.docker.internal:11434` and wires up
  `extra_hosts: host.docker.internal:host-gateway` so the JVM inside the
  container can reach the host's Ollama daemon. If Ollama is bound to
  `127.0.0.1` only and the gateway path doesn't work in your Docker
  setup, start it with `OLLAMA_HOST=0.0.0.0:11434 ollama serve`.

---
name: rhizome-devel
description: How to start, stop, and inspect the Rhizome dev environment (JVM backend + shadow-cljs frontend) and the e2e harness
---

# Rhizome dev workflow

## Dev (interactive)

```bash
make start    # boots the db-server (background), then JVM (foreground) +
              # shadow-cljs watch; ports come from exported
              # $PORT/$SHADOW_PORT/$DB_PORT if set (via direnv loading
              # .envrc, manual export, etc.); otherwise from config.edn /
              # shadow-cljs.edn (defaults 3140 / 9804 / 3141)
make start-db # the db-server alone, in the foreground
make stop     # kills all of them (only what this project bound)
```

**Two processes since the app/db split.** The db-server owns the SQLite
file and speaks statements; the app-server holds no datasource at all and
reaches it over loopback HTTP at `http://127.0.0.1:$DB_PORT`, derived from
`:db-server :port` in config.edn (an explicit top-level `:db-url` overrides
it). `make start` starts the inner one first and waits for its `/health`;
if one is already answering there it connects to that instead of starting a
second. An app-server started with nothing behind it refuses to boot and
says so — it does not come up and fail at the first statement.

The db-server also applies the schema, which the app-server no longer does.

`make start` runs the JVM in the foreground, with shadow-cljs watch
backgrounded into the same TTY — both stdouts interleave. Ctrl-C kills the
JVM. On the host, shadow-cljs keeps watching (its pid is in
`.shadow-cljs.pid`), so follow Ctrl-C with `make stop` to clean it up too.
In a container Ctrl-C tears down everything together; the EXIT trap in
`start.sh` notices both ports are free and clears `.dev-server.lock`
automatically — but only when the lock belongs to the same env (host vs.
container), so a cross-env Ctrl-C can never wipe out a still-running
session's lock.

Open the app at `http://localhost:3140` (real backend, hot reload still works
via shadow's cross-origin WS) or `http://localhost:9804` (shadow proxies API
calls to :3140).

`make start` refuses to run when anything is already listening on `PORT` or
`SHADOW_PORT` (e.g. another dev session, an in-flight `make e2e`, or
docker's port-forwarder for a running container).

## Logs

Filesystem logs (gitignored under `logs/`) — root + REST loggers go through
cambium → logback, daily-rolled:

- `logs/tracker.log` — root logger (`ROLLING`)
- `logs/rest-api.log` — REST API logger (`REST-API`)

JVM and shadow-cljs stdout/stderr stream directly to your `make start`
terminal — no longer written to `logs/dev.out` / `logs/shadow.out`.

## Tests

```bash
make test                                 # unit + integration tests against
                                          # in-memory SQLite
make e2e                                  # Playwright BDD on the dev port
                                          # (config.edn) against
                                          # ./test/rhizome-e2e.db, headless
make e2e HEADED=1                         # show the browser
make e2e T="creates a context"            # playwright -g filter, run only
                                          # scenarios whose name matches
make e2e NO_BUILD=1                       # skip the shadow-cljs release
                                          # build (reuses cached main.js;
                                          # cuts iteration time when no
                                          # cljs changed)
make e2e NO_BUILD=1 T="creates a context" # fast loop on a single scenario
```

`make test` reads `:db-server :vec-path` from `config.edn` and adds
`--exclude :vector` if the dylib it points at isn't on disk. To force-skip
even when vec is installed, remove `:vec-path` from the `:db-server` block.
(The key sat under `:semsearch` until the app/db split; `:semsearch` keeps
`:ollama-url` and `:ollama-model`, which are the app-side embedder's.)

`make e2e` and `make start` are mutually exclusive — whichever starts
first claims `.dev-server.lock` (with mode, env, headed). The other
refuses with a diagnostic naming what's holding it.

Run a single Clojure test (or namespace) as a fallback by invoking the
test-runner directly:

```bash
clj -M:test -v rest-api.queries-test/get-related-items-vector-test
clj -M:test -n et.vp.ds.search-test
```

Use the `-M` forms, not `clj -X:test :vars/:nses`. The integration suites
boot a db-server in-process (`test/integration/db_harness.clj`), and its
jetty and its idle-transaction sweeper both run non-daemon threads, so a JVM
that reaches the end of a run does not exit on its own. `-M:test` goes
through the runner's `-main`, which calls `System/exit` and takes the server
down with it; `-X` calls the function and returns, so the results print and
then the process hangs.

Run a single Playwright scenario by name:

```bash
npx playwright test -c test/playwright.config.ts --grep "creates a context"
```

`make e2e` runs `bddgen` then `shadow-cljs release app` first so the bundle
under test has no dev runtime baked in. It refuses to run when `:3140` is
up — same mutual-exclusion rule as `make start`.

## Ports at a glance

| Port | Owner |
|---|---|
| 3140 | dev JVM, the app-server (`make start`) |
| 3141 | db-server (`make start`, or `make start-db` alone) — loopback only, never published out of a container |
| 9804 | shadow-cljs primary (REPL/HMR/Inspect) |

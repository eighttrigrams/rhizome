---
name: rhizome-devel
description: How to start, stop, and inspect the Rhizome dev environment (JVM backend + shadow-cljs frontend) and the e2e harness
---

# Rhizome dev workflow

## Dev (interactive)

```bash
make start    # boots JVM on :3006 and shadow-cljs watch on :8020 / :9630
make stop     # kills both (only what this project bound)
```

Open the app at `http://localhost:3006` (real backend, hot reload still works
via shadow's cross-origin WS) or `http://localhost:8020` (shadow proxies API
calls to :3006).

`make start` refuses to run when:
- `:3005` is up — the e2e suite is in flight
- `:3006` or `:8020` is already taken

## Logs

Everything goes under `logs/` (gitignored):

- `logs/dev.out` — JVM stdout/stderr (from `clj -M:dev -m server`)
- `logs/shadow.out` — shadow-cljs watcher (compile output, errors)
- `logs/tracker.log` — root logger (cambium → logback `ROLLING`); rolls daily
- `logs/rest-api.log` — REST API logger (cambium → logback `REST-API`); rolls daily
- `logs/hooks.log` — Claude Code hook output

Tail them when something looks wrong; nothing is printed to your terminal
because `make start` backgrounds both processes.

## Tests

```bash
make test                       # unit + integration tests against ./rhizome-test.db
SQLITE_VEC_PATH=/nope make test # force-skip vector tests
make e2e                        # Playwright BDD; spawns its own JVM on :3005
                                # with ./rhizome-e2e.db (headless)
make e2e HEADED=1               # show the browser
```

`make test` auto-detects whether sqlite-vec is installed and adds
`--exclude :vector` when it isn't.

Run a single Clojure test (or namespace) as a fallback by invoking the
test-runner directly:

```bash
clj -X:test :vars '[rest-api.queries-test/get-related-items-vector-test]'
clj -X:test :nses '[et.vp.ds.search-test]'
```

Run a single Playwright scenario by name:

```bash
npx playwright test -c test/playwright.config.ts --grep "creates a context"
```

`make e2e` runs `bddgen` then `shadow-cljs release app` first so the bundle
under test has no dev runtime baked in. It refuses to run when `:3006` is
up — same mutual-exclusion rule as `make start`.

## Ports at a glance

| Port | Owner |
|---|---|
| 3005 | e2e JVM (Playwright) |
| 3006 | dev JVM (`make start`) |
| 3007 | personal prod instance — don't touch |
| 8020 | shadow-cljs `:dev-http` |
| 9630 | shadow-cljs primary (REPL/HMR/Inspect) |

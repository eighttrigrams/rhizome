# Talking to the rhizome db-server

The db-server is the process that owns rhizome's SQLite file. It speaks
statements, not items: it has no idea what a context or a relation is, and it
will never grow an endpoint that does. Everything that knows what the data
*means* lives in the app-server on the other side of this wire.

You almost certainly want the app-server's `/api` instead. This surface exists
so that the app-server can be moved to another machine, and so that the one
process holding the database can be started, stopped and inspected on its own.

## The wire

Request and response bodies are **transit-json**
(`Content-Type: application/transit+json`), except `GET /api/describe` and
`GET /health`, which answer JSON so that any prober can read them.

A statement is a vector: `[sql & params]`. Parameters are bound by the driver
and never interpolated — this is the only way to get values into a statement.

Every successful statement call answers `{:result …}`. The wrapper is there so
that a row of `nil` (no such row) is distinguishable from an empty response.
Every failure answers a non-2xx status with `{:error "…" :type …}`; a failure
that came from the database itself is additionally marked `:sql? true` and
carries its `:sql-state` and `:error-code`, so a client can rebuild the
exception its own driver would have raised.

`GET /health` asks the database before answering, so a 200 from it means
statements will actually run. It answers 503 when the database cannot be
reached — which is what a start script waiting on it needs, since a health
check that says `ok` for a database it has never touched is worse than none.

## Routes

| Route | Body | Answers |
| --- | --- | --- |
| `POST /execute` | `{:stmt [sql & params] :opts {…} :tx "token"?}` | `{:result [row …]}` |
| `POST /execute-one` | same | `{:result row}` |
| `POST /tx/begin` | `{}` | `{:tx "token"}` |
| `POST /tx/commit` | `{:tx "token"}` | `{:ok true}`, 409 while busy, 410 if unknown |
| `POST /tx/rollback` | `{:tx "token"}` | `{:ok true}`, 409 while busy, 410 if unknown |
| `GET /health` | — | `{:ok true :read-only? b :vec-available? b}`, or 503 |
| `GET /api/describe` | — | this document, and the routes above |

## Options

`:opts` carries exactly two keys, and anything else is refused rather than
ignored:

- `:builder` — how to shape a result row. The only value is
  `:unqualified-lower`, which gives `{:id 1}` where the default gives
  `{:items/id 1}`. It is a *name*, looked up in a whitelist on both ends,
  because the thing it names is a function and a function cannot be sent.
- `:return-keys` — `true` makes a write answer with the row id it generated
  instead of the number of rows it changed.

## Transactions

`POST /tx/begin` answers a token. Pass it as `:tx` on every statement that
belongs to the transaction, then `POST /tx/commit` or `/tx/rollback`. The
token names a database connection that is held open for you, so:

- **A transaction left open is rolled back.** A transaction that goes a minute
  without a statement is rolled back and freed, and its token starts answering
  `410`. The window is a boot option and the sweep runs at quarter-window
  intervals, so the real wait is between one and one and a quarter of it.
  Nothing half-written survives that.
- **A statement that is still running is never swept**, however long it takes:
  the clock is on the gap between statements, and it starts again when one
  ends. What the timeout is for is a client that went away, and a body that
  spends longer than the window between two statements is indistinguishable
  from one -- so keep whatever the transaction is waiting on outside it.
- **A token cannot begin another transaction.** `/tx/begin` refuses a request
  that carries one. A handle that is already a transaction may not be made one
  again — the same rule the app-side facade enforces locally.
- **A commit or rollback while one of your statements is still running** is
  refused with `409`, rather than closing the connection out from under it.
  Let the statement answer first.
- **One writer at a time.** This is SQLite: a write transaction takes the write
  lock when it begins, and a second one waits for it and then fails with
  `SQLITE_BUSY` rather than interleaving. That is the database's own law, and
  the db-server does not add a queue in front of it. Keep transactions short.

## Read-only mode

When the db-server was booted read-only, `/health` says `:read-only? true` and
every write fails at the driver with `SQLITE_READONLY`. The ban is structural:
the file is opened read-only, so there is no request that can get around it.
The schema is left exactly as it arrived, since applying it would be a write.

**It decides which to be for itself, from the directory it was started in.**
Prod mode — no `:dev? true` in the config.edn it read — and no `primary.nosync`
marker next to that file means read-only. That is the same rule, off the same
marker, that the app-server in front of it reaches independently, and the two
share the code that states it rather than each carrying a copy. Promoting a
replica means placing the marker and restarting **both** processes; neither
re-reads it while running.

### The two verdicts have to match, or the app-server will not boot

Reaching the rule independently is not the same as reaching the same answer.
Only the *marker* is shared by construction — it is a file, and each process
looks for it in its own working directory. `:dev?` is per **file**. So a
db-server handed a config.edn of its own sees no `:dev?` in it, concludes prod,
finds no marker beside itself, and opens the database **read-only** — under an
app-server that read a different file, believes it is a primary, and will write.

Nothing about that is visible until the first write. `SELECT 1` succeeds against
a read-only database, so a reachability check passes; `/api/status` reports
`read-only-replica: false`; and the failure arrives much later as a bare
`SQLITE_READONLY` with nothing connecting it to a marker file in another
directory.

So the app-server reads this server's `/health` at startup, compares
`:read-only?` against its own verdict, and **refuses to boot on disagreement** —
in both directions. What it prints is worth knowing, because it is the whole
constraint in three lines:

```
Refusing to start: this app-server and its db-server disagree about whether
this instance may write.
  app-server: primary -- dev mode (:dev? true in /srv/rhizome/config.edn),
    which is never a replica: no marker is consulted and none would change it
  db-server:  read-only -- it read its own config.edn and looked for
    primary.nosync in the directory IT was started in (http://127.0.0.1:3141)
A db-server reading a config.edn of its own sees no :dev? in it, so it concludes
prod, looks for primary.nosync beside itself, does not find one, and opens the
database read-only. Add :dev? true to that file, or start both processes from
this directory so they read this config.edn.
```

**Whoever runs the two processes owns this.** The rule for the standalone
arrangement, and for the day the app-server runs on another machine and reaches
this one over `:db-url`: *the db-server's config.edn must say what the
app-server's says* about the two inputs to the verdict — `:dev?`, and whether
`primary.nosync` sits beside it. Anything else is a refusal to start, which is
the point: over a network, two directories that disagree stop being exotic.

The check runs **at startup only**, like the verdict it checks. Restarting just
this server under a running app-server puts the pair back where they were
without anything noticing — which is the same reason promotion means restarting
both.

A caller that boots one in-process — the test suites do — passes `:read-only?`
explicitly instead, and no marker is consulted. No comparison happens there
either: the app-side handle in test mode is a local DataSource, so there is no
`/health` to ask.

## Starting one

```bash
clj -M:dev -m db-server                       # from a checkout
java -cp server.jar clojure.main -m db-server # from the built jar

DB_PATH=./test/rhizome-e2e.db clj -M:e2e -m db-server   # for an e2e run
```

**The e2e line needs its `DB_PATH`**, and a db-server started under `-M:e2e`
refuses to open anything else. An e2e run's `globalSetup` posts `/test/reset`,
which deletes every row in whatever database is behind it, so pointing one at
`./rhizome.db` by forgetting an export would empty the developer's own. Before
the app/db split the `-Drhizome.e2e=1` sysprop chose the file and the mistake
was not reachable; now the refusal is what keeps it that way.

Both read `./config.edn` in the directory they are launched from, and take the
whole of their configuration from its `:db-server` section:

```clojure
:db-server {:port     #long #or [#env DB_PORT 3141]
            :db-path  #or [#env DB_PATH "./rhizome.db"]
            :vec-path "./.sqlite-vec/vec0"}
```

That is the only key read as configuration, which is why the same reader serves
both arrangements: the config.edn the app-server also reads, and a standalone
file holding nothing but that section. The one thing read outside it is the
top-level `:dev?`, because the primary/replica rule above needs it and both
processes have to reach the same verdict.

Wait for `GET /health` before starting anything in front of it. `make start`
does; so does `scripts/e2e.sh`.

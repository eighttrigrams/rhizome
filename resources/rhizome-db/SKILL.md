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
| `POST /tx/commit` | `{:tx "token"}` | `{:ok true}` |
| `POST /tx/rollback` | `{:tx "token"}` | `{:ok true}` |
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
- **One writer at a time.** This is SQLite: a write transaction takes the write
  lock when it begins, and a second one waits for it and then fails with
  `SQLITE_BUSY` rather than interleaving. That is the database's own law, and
  the db-server does not add a queue in front of it. Keep transactions short.

## Read-only mode

When the db-server was booted read-only, `/health` says `:read-only? true` and
every write fails at the driver with `SQLITE_READONLY`. The ban is structural:
the file is opened read-only, so there is no request that can get around it.

It is told which to be, at boot, by whoever started it. Deciding that from the
`primary.nosync` marker in the start directory — the way the app-server decides
its own role today — is not wired up yet; nothing in this process reads that
marker.

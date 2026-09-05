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
Every failure answers a non-2xx status with `{:error "…" :type …}`.

## Routes

| Route | Body | Answers |
| --- | --- | --- |
| `POST /execute` | `{:stmt [sql & params] :opts {…} :tx "token"?}` | `{:result [row …]}` |
| `POST /execute-one` | same | `{:result row}` |
| `POST /tx/begin` | `{}` | `{:tx "token"}` |
| `POST /tx/commit` | `{:tx "token"}` | `{:ok true}` |
| `POST /tx/rollback` | `{:tx "token"}` | `{:ok true}` |
| `GET /health` | — | `{:ok true :read-only? b :vec-available? b}` |
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

- **A transaction left open is rolled back.** After 60 seconds without a
  statement the connection is rolled back and freed, and its token starts
  answering `410`. Nothing half-written survives that.
- **A token cannot begin another transaction.** `/tx/begin` refuses a request
  that carries one. A handle that is already a transaction may not be made one
  again — the same rule the app-side facade enforces locally.
- **One writer at a time.** This is SQLite: a write transaction takes the write
  lock when it begins, and a second one waits for it and then fails with
  `SQLITE_BUSY` rather than interleaving. That is the database's own law, and
  the db-server does not add a queue in front of it. Keep transactions short.

## Read-only mode

When the db-server booted against a read-only database — a replica, which is
decided by the absence of the `primary.nosync` marker in its start directory —
`/health` says `:read-only? true` and every write fails at the driver with
`SQLITE_READONLY`. The ban is structural: the file is opened read-only, so
there is no request that can get around it.

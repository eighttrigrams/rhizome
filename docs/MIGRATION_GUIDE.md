# SQLite Migration Guide

I leave this here for a while at least, as the migration from 
Postgres to SQLITE is still fresh.

---

Rhizome has switched from PostgreSQL (with `pgvector` and a `tsvector` full-text
index) to SQLite as its only supported backend. This guide covers:

1. Why we did it
2. What changed in code
3. How to migrate an existing PostgreSQL database to SQLite
4. What the migration script does (and does not) preserve
5. Known limitations and follow-ups

The whole change lives on the `migrate-to-sqlite` branch and ships in one
cutover — there is no dual-driver fallback.

## 1. Why SQLite

Rhizome is a single-user, local-first knowledge tool. Everything Postgres was
giving us — concurrent writers, strong types, advanced extensions — costs more
than it returns at this profile. SQLite gives us:

- a single file you can back up with `cp`
- no server process to start, configure, or keep alive
- trivial test harness setup (every test run can spin up a fresh DB)
- one fewer external dependency in `deps.edn`

The spike (`rhizome-spike-1`) confirmed that everything in the data layer is
mappable, with one real friction point: **vector search**. See section 5.

## 2. What changed in code

### Schema
- `schema-sqlite.sql` is now the single source of truth for the schema.
  It creates `items`, `relations`, `history`, plus an FTS5 virtual table
  `items_fts` and three triggers that keep `items_fts` in sync with `items`.
- The legacy `migrations/001-add-embeddings.sql` and
  `migrations/002-hide-in-global-search.sql` were Postgres-only (pgvector,
  `data->>'…'` JSON ops) and have been deleted. The columns they added
  (`embedding`, `hide_in_global_search`) now live directly in the schema file.
- `datastore.schema/apply-schema!` reads `schema-sqlite.sql` and applies it
  using a splitter that respects `BEGIN … END` trigger blocks (a naive
  semicolon split would break those). Both the test harness and the migration
  script use this helper.

### Search
- The free-text search clause used to be Postgres `searchable @@ to_tsquery(...)`
  on a generated `tsvector` column. It is now an FTS5 `MATCH` against
  `items_fts`, scoped via `items.id IN (SELECT rowid FROM items_fts WHERE …)`.
  Tokens are AND-ed and prefix-matched (`foo* AND bar*`).
- See `et.vp.ds.search.core/get-search-clause` and `convert-q-to-fts-query`.

### Data layer
- `datastore.dialect` used to dispatch between Postgres and SQLite. It is now
  a thin SQLite-only utility namespace (kept under the same name to avoid
  touching every caller). `now-sql`, `array-agg-sql`, and `parse-array-result`
  emit / parse the SQLite forms only.
- `et.vp.ds/create-new-item!` and `et.vp.ds/new-context` no longer rely on
  `INSERT … RETURNING` semantics (which `next.jdbc` does not surface for
  SQLite). They use a new `helpers/insert-and-get-id!` that wraps the INSERT
  and a `SELECT last_insert_rowid()` in a single transaction so the rowid
  stays on the same connection.
- Boolean columns (`is_context`, `hide_in_global_search`, `relations.show_badge`)
  come back from JDBC as `Long 0/1`. `helpers/post-process-base` now coerces
  them to real Clojure booleans via `helpers/int->bool` (because
  `(boolean 0)` is `true` in Clojure — only `nil`/`false` are falsy, which
  bit us during the migration). Read sites that bypass `post-process-base`
  (`et.vp.ds.relations`, `repository.insertion.common`, `rest-api.util`) call
  `helpers/int->bool` directly.
- `helpers/simplify-date` accepts strings as well as `java.util.Date` (SQLite
  stores dates as ISO TEXT).

### Vector search (semsearch.*)
Vector / semantic search runs on `sqlite-vec`. Embeddings live in the
`items_vec` virtual table (`vec0`, FLOAT[768]). Each connection opens with
`enable_load_extension=true` and runs `SELECT load_extension(<vec0>)` —
see `datastore.connection/make-datasource`. The legacy `items.embedding`
TEXT column from the migration is no longer read at runtime; it remains in
the schema only as a checkpoint of pgvector data.

- `semsearch.embedder/embed-text` — Ollama HTTP (URL via `OLLAMA_URL` env)
- `semsearch.backfill/embed-and-store!` — writes to `items_vec`
- `semsearch.backfill/backfill-missing!` — embeds every item missing
   from `items_vec`
- `semsearch.query/search-related-items-vector` — KNN over `items_vec`,
   filtered by relations

Embeddings are generated **on the host only** (Ollama is not reached from
inside the Docker container). The container ships sqlite-vec so the app
boots and `items_vec` is queryable, but any endpoint that calls
`embed-text` (vector search, ingestion-time embedding, the backfill REST
endpoint) will fail at the HTTP step inside the container.

### Installing sqlite-vec

- **macOS host:** `make install-sqlite-vec` — downloads `vec0.dylib` to
  `./.sqlite-vec/`. `make start` runs this as a prereq.
- **Container:** baked into the image at build time
  (`/usr/local/lib/sqlite-vec/vec0.so`); `SQLITE_VEC_PATH` is set in
  `docker-compose.yml`.

The connection layer (`datastore.connection/make-datasource`) resolves
`SQLITE_VEC_PATH` first, then falls back to a per-OS default.

### Configuration
- `config.edn`, `config.edn.template`, `e2e_config.edn`, `test_config.edn` all
  point at SQLite (`:dbtype "sqlite"`, `:dbname "./rhizome[-test|-e2e].db"`).
- `config-sqlite.edn.template` is gone — it is now the only template.

### Dependencies
- `org.postgresql/postgresql` is removed from the main `:deps`. It is added
  back **only** under the `:migrate` alias used by the one-shot data import
  (see section 3).

### Tests
- `test_config.edn` is SQLite. `et.vp.ds.search-test` applies the schema
  on load (via `datastore.schema/apply-schema!`) so test runs need no manual
  setup beyond having `schema-sqlite.sql` on disk.
- `rest-api.queries-test`'s `get-related-items-vector-test` is removed —
  the vector path is a no-op until we wire SQLite-vec or in-Clojure cosine.
  Its postgres-specific `ensure-embedding-column!` fixture is gone.
- `rest-api.mutations-test`'s pgvector fixture is gone too.

`make test` is the smoke test for "did SQLite parity hold". On the migration
branch it reports `Ran 94 tests containing 334 assertions. 0 failures, 0 errors.`

## 3. Migrating your PostgreSQL data

The migration script is at `scripts/migrate_pg_to_sqlite.clj`, exposed via
the `:migrate` alias in `deps.edn`. It is a one-shot import — run it once to
populate a new SQLite file from your old Postgres DB, then keep using SQLite.

### Inputs
- An EDN file describing the **source** Postgres connection. The same shape
  as `config.edn` (must have a `:db` key). You can hand it your old
  `config.edn` directly.
- A path for the **destination** SQLite file (will be created).

### Run
```bash
# Dry / clean run:
clj -M:migrate --src ./config.edn.pg-backup --dest ./rhizome.db

# Overwrite an existing SQLite file:
clj -M:migrate --src ./config.edn.pg-backup --dest ./rhizome.db --force
```

### What it does
1. Loads `schema-sqlite.sql` into the destination via
   `datastore.schema/apply-schema!`.
2. Disables foreign keys for the duration of the bulk insert
   (`PRAGMA foreign_keys = OFF`) so order-of-insert isn't fussy.
3. Copies `items`, then `relations`, then (if present) `history`, in id order,
   in batches of 500 inside a transaction. Explicit ids are preserved so all
   foreign-key references stay intact.
4. Type-by-type conversion:
   - `bigint` ids → `INTEGER PRIMARY KEY` (values preserved)
   - `timestamp(0)` → ISO-8601 TEXT (`YYYY-MM-DD HH:MM:SS`)
   - `date` → ISO date TEXT (`YYYY-MM-DD`)
   - `boolean` → `INTEGER` 0/1
   - `jsonb` → TEXT (the literal JSON string)
   - `vector(768)` (pgvector) → JSON-encoded float array as TEXT (lossless)
5. Resets `sqlite_sequence` for `items` and `relations` so the next
   `AUTOINCREMENT` id picks up after the largest imported id.
6. Re-enables foreign keys.
7. Verifies row counts match between source and destination — aborts with a
   non-zero exit code on mismatch.

### What it does **not** rebuild
The two index-like structures aren't migrated as data; the app rebuilds them
from the rows:

- The `items_fts` FTS5 index — populated by the `items_ai` / `items_au` /
  `items_ad` triggers as the migration script INSERTs rows. So by the time
  the script finishes, `items_fts` is already in sync.
- A vector-search index — disabled (see section 5). The `embedding` column
  is preserved as JSON TEXT, ready for whichever backend we choose.

### Caveats
- **Collation**. Postgres uses your DB's collation (often locale-aware);
  SQLite default is `BINARY`. Sort order of non-ASCII strings (umlauts) may
  differ. We have not added `COLLATE NOCASE` anywhere yet — flag it if you
  notice.
- **Embedding fidelity**. We store the pgvector text representation
  (`[0.013, -0.42, …]`) verbatim. That's lossless against the wire format
  but expands disk size compared to a packed float32 BLOB. Switch to BLOB
  if/when sqlite-vec is wired up.

## 4. Verifying the migration

A quick sanity script:

```bash
sqlite3 ./rhizome.db <<'SQL'
SELECT COUNT(*) AS items FROM items;
SELECT COUNT(*) AS relations FROM relations;
SELECT COUNT(*) AS history FROM history;
SELECT COUNT(*) AS fts_rows FROM items_fts;     -- should equal items
SELECT COUNT(*) AS with_embedding FROM items WHERE embedding IS NOT NULL;
SQL
```

Then start the app against the new file:

```bash
make start
```

and exercise:

- Free-text search (the FTS5 path)
- Item / context creation, linking, hiding
- Description history versioning

`make test` is the automated check for parity.

## 5. Known limitations & follow-ups

### Vector search (semsearch.*)
Currently a no-op. Two viable backends:

1. **sqlite-vec** — native extension, gives you `vec0` virtual tables and
   distance ops. Best perf, but adds a runtime dep that needs to be loadable
   from the JVM (`PRAGMA load_extension`, plus a platform-specific binary).
2. **In-Clojure cosine** — load all candidate rows in scope (typically a
   single context's items), parse the JSON embeddings, compute cosine, sort,
   take top N. No native dep, fine at our scale (single-user, low five-digit
   item counts at most).

When picking, also revisit the storage format — TEXT JSON is convenient but
~16 KB per 768-dim vector. A packed float32 BLOB is ~3 KB.

The data is already in place (`items.embedding` TEXT, populated from pgvector
during migration), so reintroducing semsearch is a code-only change.

### Collation
If alphabetic sort matters for non-ASCII titles, audit `ORDER BY` clauses on
text columns and add `COLLATE NOCASE` where users expect case-insensitive
ordering.

### Concurrency
SQLite serializes writes. Rhizome is single-user, so this is fine — but
worth flagging if the app ever gains a multi-tenant mode.

## 6. Rollback

If anything turns out to be broken in production:

1. The branch is `migrate-to-sqlite` — keep `main` on Postgres until you're
   satisfied.
2. The migration script is one-way; we don't have a SQLite → Postgres path.
   Keep the Postgres dump until the SQLite cutover has been running cleanly.

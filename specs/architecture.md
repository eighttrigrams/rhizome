# Architecture

Target architecture after the app-server / db-server split
(see `plans/split-db-server.md`). Two processes, one repo, one jar:
the **db-server** encapsulates the SQLite database and nothing else;
the **app-server** holds all business logic, file access, and every
outward-facing surface. The reason for the split: the db can live on
only one machine, while the files sync to every machine via iCloud.

## Diagram

```
     ┌───────────────┐          ┌───────────────────┐
     │  Browser /    │          │  Agents           │
     │  Electron SPA │          │  (plurama-cli,    │
     │  (cljs UI)    │          │   rhizome-cli.sh) │
     └───────┬───────┘          └─────────┬─────────┘
             │ POST /ui                   │ REST /api
             │ (transit-in-JSON)          │ (JSON)
             │ + static assets, /imgs/*   │
             ▼                            ▼
┌─────────────────────────────────────────────────────────┐
│                   APP-SERVER (outer)                    │
│         `-m server` · dev :3140 · prod :3007            │
│                                                         │
│   dispatch ────▶ repository ──▶ et.vp.ds.* ──┐          │    ┌─────────────────┐
│   rest-api ────▶ (queries/mutations) ────────┤          │    │ iCloud-synced   │
│   poll ── scrapers ──▶ insertion ────────────┤          │◀──▶│ file folders    │
│   upload · opener · homefolder · deletion ───┤          │    │ Images · Docs · │
│                                              │          │    │ Preview · Ingest│
│   semsearch.query / semsearch.backfill       │          │    │ Music · Movies  │
│        │                                     ▼          │    └─────────────────┘
│        ▼                              db facade (`db`)  │      (every machine)
│   semsearch.embedder                         │          │
└────────┼─────────────────────────────────────┼──────────┘
         │ POST /api/embeddings                │ POST /execute · /execute-one
         │ (HTTP, JSON)                        │ · /tx/begin|commit|rollback
         ▼                                     │ · GET /health
  ┌──────────────┐                             │ (transit over loopback HTTP)
  │    OLLAMA    │                             ▼
  │ qwen3-embed. │       ┌─────────────────────────────────────┐
  │    :11434    │       │           DB-SERVER (inner)         │
  └──────────────┘       │  `-m db-server` · dev :3141 ·       │
                         │           prod :3008                │
                         │                                     │
                         │  wire protocol · tx sessions        │
                         │  schema apply · next.jdbc           │
                         │  datasource + sqlite-vec (vec0)     │
                         └──────────────────┬──────────────────┘
                                            │ JDBC (in-process)
                                            ▼
                                    ┌────────────────┐
                                    │  SQLite file   │
                                    │  rhizome.db    │
                                    │  (`.nosync`)   │
                                    └────────────────┘
                                    (exactly one machine)

  poll/scrapers additionally reach out to the internet:
  youtube · atom feeds · substack · websites (HTTP)
```

## Edges

| Edge | Protocol | Notes |
| --- | --- | --- |
| SPA → app-server | `POST /ui`, transit args in a JSON envelope | defn-over-http shape; one endpoint, fn-name dispatch |
| Agents → app-server | REST `/api`, JSON | write-gated (recording mode), see `specs/users-authorisation-and-event-tracking.md` |
| app-server → db-server | `POST /execute`, `/execute-one`, `/tx/*`; `GET /health` — transit bodies | statements as `[sql & params]`; session transactions via token; loopback only in this phase |
| db-server → SQLite | JDBC, in-process | the only process that opens the db file; loads the vec0 extension |
| app-server → Ollama | `POST /api/embeddings`, JSON | see below |
| app-server → file folders | filesystem | iCloud-synced dirs + `Music`/`Movies`; imports, previews, deletion |
| poll/scrapers → internet | HTTP | youtube, atom feeds, substack, websites |

### The wire carries SQL, deliberately

The app-server → db-server edge is statement-level — real SQL with
parameters — and that was examined and confirmed, not inherited by
accident. This wire is not an API: it is the spinal cord of one program cut
into two halves, and nothing but the app-server is ever meant to speak it.
Agents and people talk to `/api` and `/ui`, where the gates live. The inner
server encapsulates what can only exist on one machine — the file, the
connections, the transactions, the vec extension — while the knowledge of
*what to ask* stays in `et.vp.ds`, where it always lived (the search SQL is
composed dynamically, so a fixed vocabulary could not hold it anyway).
Today the trust boundary equals the old one: only loopback can reach the
port (any other bind is refused), so whoever could speak SQL to it could
equally have opened the db file. When the wire later crosses machines, the
control that matters is **authentication** — *who* may speak — not
vocabulary: an unauthenticated higher-level endpoint would still delete
anything on request.

## Who talks to the vector embedder

**The app-server, and only the app-server.** `semsearch.embedder` is the
single component that calls Ollama (`qwen3-embedding:0.6b`, `:11434`), from
two call paths:

- **query time** — `repository` → `semsearch.query` → `embedder/embed-query`;
- **backfill** — `semsearch.backfill` walks items and embeds their
  descriptions (`POST /api/backfill/embeddings` triggers it).

In both paths the resulting vector is serialized to a JSON string
(`embedder/vec->json`) and travels as an ordinary SQL parameter through the
db facade to the db-server, which executes the `items_vec` /
`vec_distance_cosine` SQL. The db-server never contacts Ollama; its only
vector concern is loading the sqlite-vec extension so that SQL works.
Embeddings are business logic; vector *storage and distance* is db
encapsulation.

## Machine placement

- **db-server + SQLite file:** exactly one machine (today: the mac mini,
  cwd = the iCloud `Rhizome` folder; the db itself is `.nosync`, excluded
  from sync).
- **app-server:** in this phase, the same machine, connecting over
  loopback. The point of the split is the further step: app-servers on
  other machines (which already have the files via iCloud) reaching the one
  db-server over the LAN — replacing today's read-only replicas.
- **Ollama:** runs beside the app-server (it embeds queries), which in the
  multi-machine future means per app-server machine — noted as an open
  point in the plan.

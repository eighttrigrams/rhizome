# Architecture

Two processes, one repo, one jar.

- The **hub** is rhizome. It opens the SQLite file and it is the only process
  that knows what the rows mean: the schema, the vec extension, the repository,
  text *and* vector search, the embedder, the pollers, the scrapers. There is
  **exactly one**, on the machine holding the database.
- The **`server`** is *this machine*. It serves this machine's frontend and
  reaches this machine's disk, and it holds nothing about items. There is one
  per machine, the hub's machine included.

The rule, in the owner's own words: *"files handling in the server, all db
handling including vector in the hub."*

This replaces the app-server / db-server split described here before, in which
the outer process held all the business logic and spoke SQL statements to an
inner one that encapsulated only the file. That seam and the read-only replicas
that came with it retired in `handoffs/RHIZOME_ARCH_REWORK_2.md`; the reasoning
is kept below under *The wire carried SQL, deliberately — and what retired it*,
because it was a considered decision rather than an accident, and it named the
condition under which it would stop holding.

## Diagram

```
   ┌───────────────┐        ┌───────────────────┐
   │  Browser /    │        │  Agents           │
   │  Electron SPA │        │  (plurama-cli,    │
   │  (cljs UI)    │        │   rhizome-cli.sh) │
   └───────┬───────┘        └─────────┬─────────┘
           │ POST /ui                 │ REST /api
           │ (transit-in-JSON)        │ (JSON)
           ▼                          ▼
┌──────────────────────────────────────────────────┐        ┌─────────────────┐
│                  SERVER (outer)                  │        │ iCloud-synced   │
│      `-m et.rz.server.main` · dev :3140          │◀──────▶│ file folders    │
│                        · prod :3007              │        │ Images · Docs · │
│                                                  │        │ Preview · Ingest│
│  the frontend bundle · /imgs/* · /img-by-id/:id  │        │ Music · Movies  │
│  /open · /upload (bytes) · the browser gate      │        └─────────────────┘
│                                                  │         (every machine)
│  everything else is forwarded ───────────────┐   │
└──────────────────────────────────────────────┼───┘
                                               │
                     /ui · /api · /upload · /test/reset · GET /health
                                               │
                  ┌────────────────────────────┴──────────────┐
                  │  same machine: loopback                   │
                  │  another machine: ssh -N -L, and the      │
                  │  far end is still the hub's own loopback  │
                  └────────────────────────────┬──────────────┘
                                               ▼
                    ┌────────────────────────────────────────────────┐
                    │                  HUB (inner)                   │
   ┌──────────┐     │     `-m et.rz.hub.main` · dev :3141            │
   │  OLLAMA  │◀───▶│                        · prod :3008            │
   │ qwen3-e. │     │                                                │
   │  :11434  │     │  ui-api (dispatch) · rest-api · repository     │
   └──────────┘     │  et.rz.hub.ds.* · semsearch (query/backfill/   │
   (beside the hub, │  embedder) · poll · scrapers · provenance ·    │
    and only there) │  upload · dev-seed · schema · sqlite-vec       │
                    └───────────────────────┬────────────────────────┘
                                            │ JDBC (in-process)
                                            ▼
                                  ┌──────────────────────┐
                                  │  SQLite file         │
                                  │  rhizome.db.nosync   │
                                  └──────────────────────┘
                                   (exactly one machine;
                                    `.nosync` = not synced)

  poll/scrapers reach out to the internet from the hub:
  youtube · atom feeds · substack · websites (HTTP)
```

## Edges

| Edge | Protocol | Notes |
| --- | --- | --- |
| SPA → `server` | `POST /ui`, transit args in a JSON envelope | defn-over-http shape; one endpoint, fn-name dispatch. One origin, so the frontend has no idea any of this happened |
| Agents → `server` or → hub | REST `/api`, JSON | write-gated (recording mode), see `specs/users-authorisation-and-event-tracking.md`. Same surface either way |
| `server` → hub | the whole of `/ui` and `/api`, plus `/upload`, `/test/reset`; `GET /health` | one round trip per user action. Loopback on the hub's machine, an ssh tunnel from any other |
| hub → SQLite | JDBC, in-process | the only process that opens the db file; loads the vec0 extension |
| hub → Ollama | `POST /api/embeddings`, JSON | one model, one machine — see below |
| `server` → file folders | filesystem | this machine's synced copy: `/imgs/*`, `/img-by-id`, `/open` |
| hub → file folders | filesystem | the hub's own synced copy: previews written by uploads and scrapers, imports, deletion |
| poll/scrapers → internet | HTTP | youtube, atom feeds, substack, websites — from the hub, exactly once |

### What the `server` keeps, and why each one

Four things, and each is on the list for the same reason: it is meaningless
anywhere but the machine the human is sitting at.

- **the frontend bundle**, because that is the origin the browser loaded;
- **`/imgs/*` and `/img-by-id/:item-id`**, because the bytes are already on this
  machine through iCloud. `/img-by-id` asks the hub *which* file and then serves
  it itself — the question crosses the wire, the image does not;
- **`/open/:file-id`**, an OS `open` on this machine's disk;
- **`/upload`**'s HTTP multipart handling — though the bytes then go *to* the
  hub, see below.

Everything else forwards. `et.rz.placement` holds the list as data and
`et.rz.placement-sweep-test` demands that it cover `/ui`'s dispatch table
exactly, so a newly added command has to declare a side rather than default into
the hub in silence.

### Two decisions about direction that look inconsistent and are not

**`/upload` goes whole to the hub.** An upload is two writes that have to agree
— a file in `:preview-images` and the item's resource-links in the database —
and sending the bytes to the machine that owns the database is the only
arrangement where they land together. The cost is real and accepted: on a remote
machine the data goes through the tunnel and the file comes back through iCloud,
so the resource-link appears before the file does. Zero on the hub's own
machine, which is today's only case.

**`/img-by-id` goes the other way.** It is a read, the file is in a synced folder
every machine already has, and only the hub has the row that says which file. So
the lookup crosses and the bytes stay local.

The two are the same rule seen from both ends: **the small fact crosses the
wire, the large payload does not.**

## Transport: the tunnel is the security boundary

The hub binds `127.0.0.1` and **refuses a `:host` option** — passing one throws
rather than being ignored, because a single argument would otherwise publish an
unauthenticated item API to the network. A `server` on another machine reaches it
through an ssh tunnel:

```
ssh -N -L 3008:127.0.0.1:3008 mini
```

and the remote `server`'s hub address is then `http://127.0.0.1:3008` — the same
address it has on the mini. The far end of the forward is the mini's *own
loopback*, opened by its sshd — so the hub's loopback-only
invariant is preserved exactly, rather than traded away for LAN exposure plus an
authentication scheme.

Because the tunnel reproduces the hub on the remote machine's loopback at the
same port, **the same config.edn is correct on every machine**, and the only
difference between them is whether `primary.nosync` is present. See the README's
run section for the operational half.

Three consequences worth stating:

1. **The hub needs no authentication of its own**, and that is a preserved
   property rather than a new claim. ssh supplies *who may speak*.
2. **The exposed surface is safer than what it replaced anyway.** The retired
   `/execute` was "run arbitrary SQL, unauthenticated". An item-level `/api` has
   the reason-required write gate in front of it. That is a bonus on top of the
   tunnel, not the security story.
3. **Big payloads never cross it.** Images are served by each `server` from that
   machine's own synced copy; only small JSON and transit go through.

### The wire carried SQL, deliberately — and what retired it

The previous version of this document defended the statement seam at length, and
the defence was right on its own terms. It said the app-server → db-server wire
was "not an API: it is the spinal cord of one program cut into two halves", that
only the app-server was ever meant to speak it, and that the trust boundary
equalled the old one because only loopback could reach the port.

It also named, unprompted, the condition under which that would stop holding:

> When the wire later crosses machines, the control that matters is
> **authentication** — *who* may speak — not vocabulary: an unauthenticated
> higher-level endpoint would still delete anything on request.

That condition arrived. The answer to it is the tunnel, not a higher vocabulary:
ssh is the authentication, and moving the seam up is a separate improvement that
happens to also be one. So the argument was not wrong; it was conditional, and
its condition fired.

What actually retired the seam is a measurement rather than an argument.
Statements per call, counted against a seeded database:

| call | statements |
| --- | --- |
| `search/search-items` | 2 |
| `repository/fetch-item-description` | 5 |
| `repository/insert-context` (a write) | 5, in 1 transaction |
| **`repository/fetch-context`** — selecting a context, the main navigation act | **9** |
| **`repository/insert-item`** — a write | **11, in 1 transaction** |

A single search is 2 statements, which on its own argues for nothing. But the
unit the frontend actually issues is a *dispatch call*, and that is 5 to 11.
Across a network a statement seam costs nine round trips for one navigation and
holds SQLite's write lock across eleven; a call seam costs one, with the
transaction entirely inside the hub. The spinal cord was the right metaphor and
it is the reason not to stretch one across a network.

## Who talks to the vector embedder

**The hub, and only the hub.** `et.rz.hub.semsearch.embedder` is the single
component that calls Ollama (`qwen3-embedding:0.6b`, `:11434`), from two call
paths:

- **query time** — `repository` → `semsearch.query` → `embed-query`;
- **backfill** — `semsearch.backfill` walks items and embeds their descriptions
  (`POST /api/backfill/embeddings` triggers it).

In both paths the vector is serialized (`embedder/vec->json`) and used as an
ordinary SQL parameter against `items_vec` / `vec_distance_cosine`, in the same
process, over a local connection.

**This is the reason the rework exists.** With the embedder on the asking
machine, one remote vector search sends the query text to the mini's Ollama,
carries a 1024-float vector back over the tunnel, and then carries that same
vector back *down* the tunnel again as a SQL parameter — 15–20 KB of JSON floats
computed on the mini, from data on the mini, to be matched against vectors on the
mini, crossing the network twice on the way. With the embedder in the hub the
remote side sends a query *string* and the vector never leaves the machine. One
model, one machine, one call.

## Machine placement

- **hub + SQLite file + Ollama:** exactly one machine. Today the mac mini, with
  cwd the iCloud `Rhizome` folder. The database is `rhizome.db.nosync` and
  `.nosync` is exactly the suffix that excludes it from that sync, so **the
  database does not travel**. Moving the hub means moving the file by hand; it
  is not a feature and nothing here grows a mechanism for it.
- **`server`:** every machine, the mini included. On the mini it reaches the hub
  over loopback; elsewhere, through the tunnel.
- **file folders:** every machine, via iCloud. That sync stays and carries images
  and previews — it is what lets each `server` serve `/imgs/*` locally. Only the
  db is in one place.

### Which machine is the hub

`primary.nosync`, beside `config.edn` in the directory the processes start from,
and gitignored so no checkout has one. It used to mean *may this instance write*;
it now means *this machine runs the hub*. `et.rz.hub.main/check-elected!` refuses
to boot a hub without it, outside dev.

The refusal matters more than it did. Two writers on one file would at least be
one file. Two hubs are **two databases diverging in silence**, because the db is
the one thing the sync does not carry — found whenever the owner next notices
something missing.

That check catches a hub that *starts* unelected. It cannot catch one that is
already running, so **demoting a machine means stopping its hub, not just
removing the marker.** A guard is available cheaply and is not built: a hub that
finds another hub answering `/health` through the tunnel at boot could refuse to
start.

### There is no degraded mode

A `server` whose hub is unreachable answers 502, and refuses to boot at all if
the hub is already unreachable at startup. Offline reading was a capability the
read-only replicas had; it was not one in use, and it was given up deliberately.
**Hub down means that machine is down, not degraded.**

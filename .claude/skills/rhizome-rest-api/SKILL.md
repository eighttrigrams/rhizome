---
name: rhizome-rest-api
description: Hitting a locally running Rhizome's REST API during development, with the bundled rhizome-cli.sh curl wrapper — /api/describe, the write gate and recording mode, embedding backfill, response shapes. The full query guidance (the rhizome-user skill) is served by the API itself, as the skill key of GET /api/describe.
---

# Rhizome REST API (development)

This skill is for **development against a locally running Rhizome** — you
started it yourself, you know the port, and you are exercising or changing the
API.

How to search well — the item/context model, intersection search, kind
filters, search modes, vector search — is the `rhizome-user` guidance, which
the API serves about itself: the `skill` key of `GET /api/describe`. It
applies unchanged here.

Rhizome exposes its REST API at `http://127.0.0.1:<port>/api/`. Dev port is
`3006`. Request/response bodies are JSON. Rhizome must be running locally
(`./dev.sh` in `rhizome/`).

Script: ${CLAUDE_SKILL_DIR}/scripts/rhizome-cli.sh

Thin wrapper around `curl` against `127.0.0.1`. The port is required. Pass the
path after `/api`, **including the leading `/`** (the script does not duplicate
the API surface; you must know the endpoints).

Usage
```
rhizome-cli.sh <port> <path>                        # GET
rhizome-cli.sh <port> <method> <path> [json-body]   # any method, optional JSON body
```

```bash
${CLAUDE_SKILL_DIR}/scripts/rhizome-cli.sh 3006 "/contexts?q=Books"
${CLAUDE_SKILL_DIR}/scripts/rhizome-cli.sh 3006 "/items/10935/related?secondary_ids=11041&search_mode=2"
```

## Endpoint catalogue — ask the server

`GET /api/describe` returns a self-description of every handler (name,
arglists, docstring with method, path, params, and status codes). That endpoint
is the authoritative reference — never guess an endpoint from memory.

```bash
${CLAUDE_SKILL_DIR}/scripts/rhizome-cli.sh 3006 /describe | jq
```

## Writing and the recording gate

`POST /api/contexts`, `POST /api/items`, and `PUT /api/items/:id` all first
log the intended action, then either execute (when recording is on) or drop the
request with `403 {"dropped":true,"recording":false,"intent":"..."}`.

To enable recording: inside the running Rhizome app, press **Option+Shift+W**.
A red ⚠ REC badge appears in the top-left corner while the mode is active.
Toggle it off with the same shortcut. Every attempted write is logged to
`rhizome/rhizome.log` regardless of whether it was executed or dropped.

Two endpoints have a door in the shut gate. Both are logged with
`gate-bypassed=true` on the intent line, so that is what to grep for when a
write appears that the badge says should not have. Both go by the handle and
not by the title, and neither creates the `imports` context, so while no
context carries the handle there is no door at all.

- `POST /api/items` — when `context-ids` names `imports`, the item is written
  with recording off, whatever else is named alongside.
- `PUT /api/items/:id` — when the item has **no description yet**, the
  description is written with recording off, and the item is also filed under
  `imports`. Filing under `imports` happens on the door path only — with
  recording on, the endpoint replaces the description and touches nothing else.

Through a door you may add and only add. That is what makes them safe to leave
open, and it is why both refuse rather than update:

| situation | answer |
|---|---|
| `POST` names a URL already in the graph | `409 {"collision":true,"existing-item-id":…}` |
| `PUT` at an item that already has a description, gate shut | `409 {"collision":true,"item-id":…}` |
| gated for any other reason | the drop stub — `403 {"dropped":true}`, or for `POST`/`PUT` on items a 2xx echo of what the write would have looked like |

A 409 is a standing refusal: sending it again changes nothing, and the way
through is the app. A drop just means the gate is shut right now.

URLs (YouTube, GitHub, Substack, …) passed as `title` on `POST /api/items` are
auto-detected and enriched by the insertion pipeline, but only under
`?scrape=true`. Without it nothing is fetched and the title is stored as sent.
What was scraped is stamped provenance `scraper`, everything else `api`.

`POST /api/items` creates and does nothing else — that is what makes the door
safe to leave open. A title an ingester recognises as something the graph
already holds is refused with `409 {"collision":true,"existing-item-id":…}` and
nothing is written: not the contexts on the request, not the description, not
the sort index. To file an existing item under another context use
`PUT /api/relations`, and to replace its description `PUT /api/items/:id` —
both need recording mode on. Pasting the same link inside the app does file it
under the context you are standing in; the API deliberately does not.

## Embedding backfill

`POST /api/backfill/embeddings` embeds every item that has a description and a
NULL embedding. Idempotent and resumable — safe to re-run (or interrupt and
re-run). Gated by recording mode. The request blocks until completion and
returns `{"embedded": N}` (or `{"embedded": 0, "dry-run": true}` when recording
is off).

```bash
${CLAUDE_SKILL_DIR}/scripts/rhizome-cli.sh 3006 POST "/backfill/embeddings"
```

Run this after bulk-ingest scripts (the rhizome-ingest scripts already call it
at the end), or periodically if items were created via the UI (the UI path
bypasses the per-item embed hook). Only items with a non-empty description get
embedded — on ingestion (`POST /api/items`, `PUT /api/items/:id`) and here;
title-only items are intentionally skipped.

Embeddings come from local Ollama (`nomic-embed-text`, 768-dim) and are matched
against `items.embedding` via pgvector.

## Response shape

List endpoints return JSON arrays of item objects. A single item looks like:

```json
{
  "id": 34696,
  "title": "...",
  "short-title": "...",
  "is-context": false,
  "description": "...",
  "inserted-at": "...",
  "updated-at": "...",
  "contexts": { "10935": "Second World War", "11041": "Quotes" }
}
```

`with-related` returns `{"item": {...}, "related": [{...}, ...]}`.

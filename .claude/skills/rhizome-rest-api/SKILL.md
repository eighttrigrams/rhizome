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

Every mutating endpoint first logs the intended action, then either executes
(when recording is on) or drops the request. A drop is not an error status: each
endpoint answers with a stub in the shape of its success, `2xx` — `POST
/api/items` `201 {"created":true}`, `PUT /api/relations` `200` with the source
item as it stands, `POST /api/backfill/embeddings` `200 {"dry-run":true}`, `POST
…/related/delete` `200 {"dropped":true}`. Nothing in the answer says it was
dropped except the stub's shape; the log says it.

To enable recording: inside the running Rhizome app, press **Option+Shift+W**.
A red ⚠ REC badge appears in the top-left corner while the mode is active.
Toggle it off with the same shortcut. Every attempted write is logged to
`rhizome/rhizome.log` regardless of whether it was executed or dropped.

Four endpoints have doors in the shut gate. All are logged with
`gate-bypassed=true` on the intent line, so that is what to grep for when a
write appears that the badge says should not have. The two import doors go by
the handle and not by the title, and nothing creates the `imports` context with
the gate shut, so while no context carries the handle there is no import door
at all.

- `POST /api/items` — when `context-ids` names `imports`, the item is written
  with recording off, whatever else is named alongside.
- `PUT /api/items/:id` — two doors.
  - Import door: when the item has **no description yet** and `imports`
    exists, the description is written with recording off, and the item is
    also filed under `imports`. Filing under `imports` happens on this path
    only — with recording on, the endpoint replaces the description and
    touches nothing else.
  - Description door: otherwise, the description is written when **no line of
    the owner's degrades** (`provenance/degradations` over
    `et.uvt.caution/degradations`): each of his lines still there, none with
    lower caution than now. Files nothing, needs no `imports`. Check and write
    in one transaction — the import door's write goes through the same
    re-check, and a refusal found inside it is logged with
    `refused-in-transaction=true` after the `gate-bypassed` line. Appending
    after his text and rewriting agent-only stretches pass. Inserting between
    two of his paragraphs passes only where the caution ranges show two
    separate islands: text he wrote in one go is one island, blank lines
    included, so a paragraph put between two of his is inside it.
    Refused: changing a line of his (= gone, and the new line lowers the rest
    of its island); moving one (= gone); one line inserted into his island
    (every line of his in that island is reported, also far ones); a
    recurring line of his, e.g. a blank, between two rewritten neighbours
    (gone). Known gaps: a block of an agent's bigger than the island piece it
    cuts off becomes its own island, nothing reported. The same gap lets a
    pebble through in two writes: insert a two-line brick, then delete one
    line of it, and one agent line sits inside his paragraph while his
    caution never drops. An empty current description has nothing of his to
    lower.
- `POST /api/contexts` — always, with recording off: a new context rewrites
  nothing. Refused (`409 {"refused":"imports-handle"}`) when
  `human-readable-id` is `imports`.
- `PUT /api/items/:id/is-context` with `{"is-context": true}` — the item is
  made a context when it is **machine-made throughout**: every version of it,
  current row and history (`ds/get-description-history`), has a non-blank
  source outside `provenance/ours` (`app`, `obsidian`). A blank source is the
  owner's, so legacy items never qualify. `{"is-context": false}` (context →
  item) is never let through a shut gate. With the gate open both directions
  go, through the app's own `switch-between-item-and-context!` and its rule
  that a context filed under nothing stays a context. Asking for the state the
  item is already in answers 200 with `"unchanged": true`.
  Known gap: machine-made looks only at the sources of title and description.
  Filing, tags and annotations the owner gave the item are not consulted, so
  an agent-made item he has filed and annotated by hand still qualifies.

`PATCH /api/items/:id` has **no door**. It changes `title`, `short-title`,
`tags`, `annotation`, `date`, `sort-idx`, `hide-in-global-search` and
`human-readable-id` by compare-and-set — `{"reason":…, "changes":{"title":
{"old":"Foo","new":"Bar"}}}` — and with the gate shut it is an ordinary drop:
logged (`intent=patch-item`, no `gate-bypassed`) and answered `200` with the
item as it stands. With the gate open every `old` is compared, inside the same
transaction as the write, against what `GET /api/items/:id` serves (absent =
`null`, so a false `hide-in-global-search` is `null`); a mismatch is `409
{"refused":"old-values-differ","mismatched":{<prop>:{"sent","current"}}}`,
nothing written. A held `human-readable-id` is `409 "human-readable-id-taken"`.
`400` for an unknown or unpatchable property (description, is-context and
contexts have their own endpoints), a missing `old`/`new`, a wrong type; `404`
for an unknown item. A title change goes to the history and stamps the item
`api`, as the app's own save does.

Through a door you may add and only add. That is what makes them safe to leave
open, and it is why they refuse rather than update:

| situation | answer |
|---|---|
| `POST` names a URL already in the graph | `409 {"collision":true,"existing-item-id":…}` |
| `PUT /api/items/:id`, gate shut, would lower a line of the owner's | `409 {"refused":"degrades-owner-lines","degraded":[{"line","text","before","after"}]}` — `after` null for a line that would be gone, `line` one-based in the current description |
| `POST /api/contexts` with handle `imports`, gate shut | `409 {"refused":"imports-handle"}` |
| `PUT …/is-context` true, gate shut, a version of the owner's | `409 {"refused":"not-machine-made"}` |
| `PUT …/is-context` true, gate shut, the item with handle `imports` | `409 {"refused":"imports-handle"}` |
| `PUT …/is-context` false, gate shut | `409 {"refused":"demote"}` |
| `PUT …/is-context` false, a context filed under nothing (any gate) | `409 {"refused":"in-no-context"}` |
| gated for any other reason | the endpoint's `2xx` drop stub (see above); `PUT /api/items/:id` has none — with the gate shut it is a door or a refusal |

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
  "tags": "...",
  "sort-idx": -1,
  "is-context": false,
  "description": "...",
  "inserted-at": "...",
  "updated-at": "...",
  "contexts": { "10935": "Second World War", "11041": "Quotes" }
}
```

`with-related` returns `{"item": {...}, "related": [{...}, ...]}`.

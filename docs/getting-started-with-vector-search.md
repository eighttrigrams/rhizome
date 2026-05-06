# Getting Started - With Vector Search

This guide walks through bringing up Rhizome from a fresh clone with vector
search enabled.

## 1. Preconditions

Start with a fresh clone of this git repo (or `git clean -xfd` an existing
checkout to wipe build artefacts, ignored files, and local databases).

Install and start Ollama with the `nomic-embed-text` model — Rhizome calls it
to turn descriptions and queries into 768-dim vectors.

```bash
brew install ollama # or your platform's installer
ollama pull nomic-embed-text
ollama serve        # listens on http://127.0.0.1:11434
```

Then bring up Rhizome:

```bash
make install-sqlite-vec
make onboard
```

When `make onboard` finishes the dev server is already running on
`localhost:3006`.

## 2. Add items with descriptions

Open the app at `localhost:3006`, pick a context (e.g. `Documents`) and
create a few items. Give each one a **non-empty description** — title-only
items are skipped on purpose by the embedder and will never appear in vector
results.

Item creation through the UI **bypasses the per-item embed hook**, so the
items you just made have a NULL `embedding` until you backfill (next step).
The REST endpoints (`POST /rest/items`, `PUT /rest/items/:id`) do embed on
write, so anything created through them is already searchable.

## 3. Backfill embeddings

Embedding writes are gated by **recording mode**. Press **Option+Shift+W**
in the running app — a red ⚠ REC badge appears top-left — then:

```bash
make backfill-embeddings
```

Without recording you'll see `{"embedded":0,"dry-run":true}` and nothing is
written. With recording on you'll see `{"embedded":N}`. The endpoint is
idempotent and resumable.

## 4. Run a vector search

```bash
# find the context id
curl -s "http://127.0.0.1:3006/rest/contexts?q=Documents" | jq

# vector search inside that context (URL-encode multi-word queries)
curl -s "http://127.0.0.1:3006/rest/items/<id>/related?vector=true&q=greeting" | jq
```

The same query path is reachable from the search box in the UI once a
context is selected.

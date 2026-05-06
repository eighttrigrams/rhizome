# Getting Started - With Vector Search

Preconditions:

Start with a fresh clone of this git repo

```bash
brew install ollama # or your platform's installer
ollama pull nomic-embed-text
ollama serve # listens on http://127.0.0.1:11434
make install-sqlite-vec
make onboard
```

Visit `localhost:3006`

Add items with a description to a context of your choice. Only items with a
non-empty `description` get embedded — title-only items are skipped on
purpose and will never appear in vector results. Item creation through the
UI **bypasses the per-item embed hook**, so freshly added items have a NULL
`embedding` until you backfill (see below). The REST endpoints
(`POST /rest/items`, `PUT /rest/items/:id`) do embed on write.

Enable recording mode (press **Option+Shift+W** in the running app — a red
⚠ REC badge appears top-left) and call

```
make backfill-embeddings
```

Without recording the response is `{"embedded":0,"dry-run":true}` and
nothing is written. With recording on you'll see `{"embedded":N}`.

To search:

```bash
# 1) find the context id
curl -s "http://127.0.0.1:3006/rest/contexts?q=Documents" | jq

# 2) vector search inside that context (URL-encode multi-word queries)
curl -s "http://127.0.0.1:3006/rest/items/<id>/related?vector=true&q=greeting" | jq
```

In the UI, the same query path is reachable from the search box once a
context is selected.

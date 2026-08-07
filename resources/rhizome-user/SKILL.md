---
name: rhizome-user
description: How to search and read the human's rhizome (their long-term memory / second brain / idea store) over HTTP — the item/context graph model, context-intersection search, kind filters, semantic search, and the write gate. Use whenever a question means looking something up in rhizome.
---

# Using rhizome

Rhizome is the human's long-term memory / second brain and idea store. It is
reached over HTTP: every endpoint lives under `/api`; for brevity the paths
below are written without that prefix. Examples are curl syntax, with
`$RHIZOME` standing for the base URL up to and including that prefix.

```bash
curl -s "$RHIZOME/contexts?q=Books"
curl -s "$RHIZOME/items/10935/related?secondary_ids=11041&search_mode=2"
```

Quote the URL so the shell keeps `?` and `&`.

When the human mentions books, also load the `rhizome-books` skill.

## Model

Everything is an **item**. Items flagged `is_context: true` are **contexts** —
they act as categories/topics/collections and group other items. A "search" in
rhizome typically means: find the relevant context(s), then list items related
to them — ideally using *intersection* search across multiple contexts.

### Topic vs. kind-of-item contexts

Two distinct flavours of context coexist and are routinely intersected:

- **Topic contexts** — what an item is *about* or where it belongs (e.g. a
  book, a chapter, "Second World War", "Preface").
- **Kind-of-item contexts** — what an item *is* (e.g. `Page`, `📚❝❞` Quote,
  `People`). An item being a member of a kind-context is how its type is
  expressed in the data model.

When the human names both a kind ("pages", "quotes", "people") **and** a topic
or scope ("of the preface", "about Wittgenstein"), this is a type filter —
intersect topic ∩ kind. See the recipe below.

## Endpoint catalogue — ask the server

`GET /describe` returns a self-description of every endpoint (its name, and a
docstring giving method, path, params and status codes) alongside the
conventions that hold across the API. That endpoint is the authoritative
reference — this skill covers *how* to search, not the catalogue.

```bash
curl -s "$RHIZOME/describe" | jq
```

## Search recipes

### Find the right context first, then drill down

```bash
curl -s "$RHIZOME/contexts?q=Books"
curl -s "$RHIZOME/items/10935/related"
```

### Intersection search (preferred strategy)

The selected context defines the working set; each id in `secondary_ids`
further constrains it (logical AND). Put the **narrowest / most specific**
context as the selected id, the additional constraints as `secondary_ids`.
With secondary ids present the limit rises from 10 to 100.

```bash
# Quotes that also belong to "Second World War"
# (Second World War is narrower → selected; Quotes is the kind filter → secondary)
curl -s "$RHIZOME/items/10935/related?secondary_ids=11041"
```

**Kind filter recipe.** When the human asks for "<kind> of/in/about <topic>"
(e.g. "pages of the preface", "quotes about virtue", "people in this book"):

1. Resolve the topic context → selected id.
2. Resolve the kind context (Page, Quote, People, …) → `secondary_ids`.
3. Issue intersection. Without it, the result mixes kinds (e.g. pages **and**
   their quotes appear together under the same topic context).

```bash
# "pages of the Preface" → Preface (topic) ∩ Page (kind)
curl -s "$RHIZOME/items/49041/related?secondary_ids=48601&search_mode=2"
```

**Prefer the minimal intersection that captures the human's intent.** Adding
ancestor topics on top of a working kind filter (e.g. also intersecting with
the enclosing book) does not broaden the result — it narrows it, and risks
excluding items whose context membership isn't fully populated. Only add a
secondary id if the query actually constrains on it.

### Search modes

`search_mode` tweaks the ordering of related-items results:

- `0` (default) — most recently touched first
- `2` — ordered by `sort_idx` (e.g. page numbers for book quotes); raises the
  result limit to 5000
- `5` — most recently added first

### Get an item with its description and neighbourhood

Only for *non-context* items (leaf notes with longer titles).

```bash
curl -s "$RHIZOME/items/34696/with-related"
```

### Free-text item search (use sparingly)

Prefer the context/intersection approach above. Fall back to `q` on
`GET /items` only when you can't narrow by context.

```bash
curl -s "$RHIZOME/items?q=Wittgenstein"
```

### Finding people

People are items under a dedicated "People" context. Once you have that
context's id, use `GET /items/:id/related`:

```bash
curl -s "$RHIZOME/items/<people-ctx-id>/related?q=Daniel"
```

### Semantic / vector search

`GET /items/:id/related` takes `vector=true` to switch from SQL LIKE to
cosine similarity on embeddings.

```bash
curl -s "$RHIZOME/items/9659/related?vector=true&q=history%20of%20oil"
```

Important: **only items with a non-empty description get embedded**.
Title-only items are intentionally skipped and will never appear in vector
results — so a miss in vector search is not evidence that nothing is stored.

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

`GET /items/:id/with-related` returns `{"item": {...}, "related": [{...}, ...]}`.

## Writing

Rhizome is the human's memory — **do not write to it unless the human
explicitly asks**. Reads are free; a write puts machine-authored text into a
store whose value rests on its provenance.

When asked, the write endpoints are `POST /contexts`, `POST /items` and
`PUT /items/:id`. Three rules govern every one of them.

**A mutation must say why it is happening.** Every POST/PUT/PATCH/DELETE body
needs a non-blank `"reason"` field explaining the change; it is recorded in the
server logs. Without one the request comes back `400` and nothing happens.

```bash
curl -s -X POST "$RHIZOME/items" -H 'Content-Type: application/json' \
  -d '{"title":"…","context-ids":[10935],"reason":"the human asked me to note …"}'
```

**Mutations are gated by recording mode**, which the human toggles in the app
(a red ⚠ REC badge shows while it is on). With recording off the request comes
back `403 {"dropped":true,"recording":false,"intent":"..."}` and nothing is
stored; the attempt is logged either way. Never ask for the gate to be
bypassed — report the drop and let the human decide.

**Some instances take no writes at all.** The human's rhizome directory is
synced across machines, and only the one holding the `primary.nosync` marker may
write; every other copy runs as a read-only replica and answers each mutation
with `403 {"read-only-replica":true}` (its database is open read-only, so
nothing can slip through). `GET /status` tells you which one you are talking to.
Reads work exactly as everywhere else — report the refusal and let the human
write on their primary.

URLs (YouTube, GitHub, Substack, …) passed as `title` on `POST /items` are
auto-detected and enriched by the insertion pipeline.

### Part-of relations

`PUT /relations` also carries a second, sparse layer over the plain relations.
Marked with `"is-part-of": true`, a relation says the **target** item is the
whole and the **source** item one of its parts — a chapter of a book rather
than merely a note about it — and `"part-of-sort-idx"` places it among the
other parts of that whole. That index is a plain integer and the parts are
listed by it ascending; left at `-1`, the default, the part has no place yet and
sorts after every sibling that has one. The index belongs to the edge, not to
the item: a part sitting under several wholes takes a different position under
each, and it is independent of every other sort index.

```bash
curl -s -X PUT "$RHIZOME/relations" -H 'Content-Type: application/json' \
  -d '{"source-id":13,"target-id":12,"is-part-of":true,"part-of-sort-idx":1,
       "reason":"the human asked me to file this chapter under the book"}'
```

These edges form a **directed acyclic graph**, not a tree. A node may be part
of several wholes; do not assume a unique parent or a unique path to a root.
What is not allowed is a cycle: a write that would close one comes back `409`
with the path that would have closed it, in `error` and as ids in
`part-of-cycle`. Plain relations are not constrained this way.

## Search strategy — when using rhizome for research

1. Break queries into likely categories. Prefer two short searches on separate
   terms over one long multi-word query.
2. `GET /contexts?q=…` first, to find the relevant context ids.
3. `GET /items/:id/related?secondary_ids=…` for intersection search — much
   better than free-text `q` on `GET /items`.
4. Only use free-text `q` when you cannot narrow by context.
5. Result lists are "most recently touched first" by default — top results
   are literally "top of mind" and should be weighted higher.

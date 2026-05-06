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
make backfill-embeddings
```

Visit the Articles context. 
Search for Lemons.
The Market-for-Lemons article should rank first.

# Vector / semantic search

Item search has an opt-in semantic mode: while the items input is open,
press **Shift+Option+V** to toggle it. The input gets a green outline,
sort modes are bypassed, and results are ranked by cosine similarity
against `items.embedding`. Secondary-context filters still apply.

The same path is also exposed at the REST API as
`GET /rest/items/:id/related?vector=true&q=…` (see `src/clj/rest_api.clj`).

### Runtime requirements

- **sqlite-vec** — the `vec0` SQLite extension is loaded on every JDBC
  connection (`src/clj/datastore/connection.clj`). On Mac the loader
  defaults to `./.sqlite-vec/vec0`; install it once with
  `bin/install-sqlite-vec.sh`. On Linux it defaults to
  `/usr/local/lib/sqlite-vec/vec0`. `SQLITE_VEC_PATH` overrides both.

- **Ollama** — query embedding is done by Ollama, default
  `http://127.0.0.1:11434`, model `nomic-embed-text` (768-dim). Both are
  configurable via `OLLAMA_URL` and `OLLAMA_EMBED_MODEL`. Items are
  embedded the same way at insertion time, so query and corpus must use
  the *same* model — switching it invalidates existing embeddings.
  `ollama pull nomic-embed-text` and `ollama serve` on the host.

- **Embedding coverage** — only items with a non-empty description are
  embedded; title-only items never appear in vector results. After bulk
  ingest or a model switch run `POST /rest/backfill/embeddings`
  (idempotent, resumable, gated by recording mode).

### Running through Docker

`docker-rhizome/` ships a dev container that bundles all of the above
except Ollama itself (which stays on the host):

- **Base image is `clojure:temurin-21-tools-deps-bookworm-slim`, not
  Alpine.** sqlite-vec's prebuilt linux loadable is built against glibc
  with FORTIFY_SOURCE; Alpine + `gcompat` doesn't provide
  `__memcpy_chk`, so `vec0.so` fails to relocate at load time. Debian
  slim resolves all symbols against the system glibc.

- **sqlite-vec 0.1.9, not 0.1.6.** The 0.1.6 release ships a mislabelled
  `linux-aarch64` asset (it's actually a 32-bit ARM build) and fails on
  aarch64 hosts with "Exec format error". 0.1.9 ships a real 64-bit
  aarch64 binary.

- **The Dockerfile picks the right slug from `uname -m`** so the same
  image definition builds correctly on x86_64 *and* aarch64 (Apple
  Silicon) hosts.

- **Ollama runs on the host, not in the container.** The compose file
  sets `OLLAMA_URL=http://host.docker.internal:11434` and wires up
  `extra_hosts: host.docker.internal:host-gateway` so the JVM inside the
  container can reach the host's Ollama daemon. If Ollama is bound to
  `127.0.0.1` only and the gateway path doesn't work in your Docker
  setup, start it with `OLLAMA_HOST=0.0.0.0:11434 ollama serve`.

### Operational notes (gotchas we hit)

- **Prod doesn't auto-install the extension.** `deploy.sh` and
  `start.sh` don't call `bin/install-sqlite-vec.sh`; only `make start`
  does. After bumping the version (or on a first deploy), SSH to prod
  and run the installer once with the right destination —
  `datastore.connection/vec-extension-path` defaults to
  `/usr/local/lib/sqlite-vec/vec0` on Linux, so:
  ```
  sudo ./bin/install-sqlite-vec.sh /usr/local/lib/sqlite-vec
  ```
  The script writes a `vec0.<ext>.version` stamp next to the binary and
  re-downloads only when the stamp is missing or doesn't match
  `SQLITE_VEC_VERSION`, so subsequent runs are no-ops. `SQLITE_VEC_PATH`
  on prod can override the default location.

- **0.1.6 has a `vec0` MATCH bug we hit in tests.** kNN over a freshly
  populated `items_vec` with very few rows fails with "Error opening
  vector blob at main.items_vec_vector_chunks00.<n>". The chunk-rowid
  math drifts and SQLite is asked to open a non-existent blob. 0.1.9
  fixes the symptom for our test shape, but the path stayed brittle
  enough across versions that
  `test/rest_api/queries_test.clj :: get-related-items-vector-test`
  is currently `#_`-gated until a release we trust everywhere lands.
  See the comment block above the test for the full story and the
  re-enable checklist.

- **macOS SIGKILLs `java` on dylib mtime drift.** If you `cp`,
  `touch`, `xattr -c`, or otherwise change the mtime of an already-
  loaded `vec0.dylib`, the kernel's code-signing cache marks the
  mapped pages tainted and SIGKILLs every process that has it mapped
  — visible only as `make: *** [test] Killed: 9` with no Java output.
  Confirm via `log show --last 5m | grep cs_mtime` (look for
  `tainted:1` against `vec0.dylib`). Fix is to remove and reinstall
  cleanly, then leave the file alone:
  ```
  rm -rf ./.sqlite-vec && ./bin/install-sqlite-vec.sh
  ```

- **Test JVM SIGKILL with no output is almost always the dylib.**
  `cognitect.test-runner` prints `Running tests in #{"test"}` before
  it requires test namespaces, and that's where `vec0.dylib` first
  gets `dlopen`'d (transitively, via `datastore.connection`). If the
  JVM dies right after that line with `Killed: 9` and no stack, check
  the kernel log before chasing memory pressure or test logic.

# docker-rhizome

Run Rhizome dev inside an isolated container.

## What's inside the container

One image, built in two stages from `Dockerfile`. `base` carries the dev
toolchain; `box` is a plain dev shell on top of it, running as root:

JDK 21 + `clj`, Node 22 + npm, `bb`, `make`, `git`, `sqlite3`, `jq`, `socat`,
`lsof`, `imagemagick`.

No browser: `make e2e` from inside the box refuses by design, with a message
pointing you at the host (see `scripts/e2e.sh`).

Then on the host: open `http://localhost:3140` — or whatever `PORT` resolves
to, if you have moved it (see `scripts/detect-ports.sh`).

## SQLITE Vec

Off by default. Set `WITH_VEC=1` when building to enable semantic search;
the image then bundles `sqlite-vec`, Ollama, and the `qwen3-embedding:0.6b`
model, so semsearch works inside the container with no host-side install.

```bash
make box WITH_VEC=1
```

The model is the one part that lives in a volume rather than the image. That
volume, `rhizome_ollama_models`, is declared `external: true` so it is shared
per machine rather than per checkout — a second clone finds the model already
there instead of pulling ~640 MB again. `make box` creates it first
(`docker volume create` is idempotent), because `external: true` disables
compose's auto-creation.

Vector-dependent tests are tagged `^:vector`. `make test` looks at
`:semsearch :vec-path` in `config.edn` and adds `--exclude :vector` if
the dylib it points at isn't on disk. To force-skip even when vec is
installed, remove the `:semsearch` block from `config.edn`.

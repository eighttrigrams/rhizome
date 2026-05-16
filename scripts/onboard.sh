#!/bin/bash
set -e

echo "=== Rhizome Developer Onboarding ==="

if [ -f "config.edn" ]; then
    echo "config.edn already exists. Remove it first (or 'make clean') if you want to reset."
    exit 1
fi

echo "Creating SQLite configuration..."
# :port reads from $PORT via aero's #env so the actual port is driven by
# .envrc / Makefile / docker env at runtime, not baked into this file. 3006
# is the fallback when nothing else is set.
# :semsearch is only written when this onboard was launched with WITH_VEC=1
# (either `make box WITH_VEC=1 && make onboard` inside the container, or
# `WITH_VEC=1 ./scripts/onboard.sh` on the host). Without it, semsearch is
# disabled, which is what we want for users who don't need vector search.
if [ "${WITH_VEC:-0}" = "1" ]; then
    # :vec-path is the sole source of truth for where the sqlite-vec
    # extension lives. Linux/Docker installs to /usr/local/lib, Mac/host
    # installs to ./.sqlite-vec via scripts/install-sqlite-vec.sh.
    case "$(uname -s)" in
        Linux) VEC_PATH="/usr/local/lib/sqlite-vec/vec0" ;;
        *)     VEC_PATH="./.sqlite-vec/vec0" ;;
    esac
    SEMSEARCH_LINE=$'\n :semsearch {:vec-path "'${VEC_PATH}$'"\n             :ollama-url "http://127.0.0.1:11434"\n             :ollama-model "nomic-embed-text"}'
else
    SEMSEARCH_LINE=""
fi
cat > config.edn <<EOF
{:port #long #or [#env PORT 3006]
 :dev? true
 :db {}${SEMSEARCH_LINE}}
EOF

echo "Creating directories..."
mkdir -p files/Documents/Tracked
mkdir -p files/Downloads/Tracked
mkdir -p files/Movies/Tracked
mkdir -p files/Music/Tracked
mkdir -p files/Pictures/Tracked/Preview/Lowres

echo ""
echo "Done. Next:"
# The dev DB is auto-created and seeded by the JVM on first `make start`
# (when :dev? true and no items are present yet). Set :skip-seed? true in
# config.edn to skip seeding. Inside the dev container `npm install` is
# already done by entrypoint.sh on first entry, so don't tell the user to
# run it again. /.dockerenv is the standard "are we in a docker container"
# marker.
if [ ! -f /.dockerenv ]; then
    echo "  npm install      # one time"
fi
echo "  make start       # boots JVM (auto-creates+seeds dev db) + shadow-cljs"
case "$(uname -s)" in
    Darwin) VEC_EXT="dylib" ;;
    Linux)  VEC_EXT="so" ;;
    *)      VEC_EXT="" ;;
esac
VEC_LIB="./.sqlite-vec/vec0.$VEC_EXT"
if [ -n "$VEC_EXT" ] && [ -f "$VEC_LIB" ]; then
    echo "  make backfill-embeddings   # embed the seeded articles"
fi
echo "Then visit http://localhost:${PORT:-3140}"

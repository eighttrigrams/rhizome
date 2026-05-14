#!/bin/bash
set -e

echo "=== Rhizome Developer Onboarding ==="

# Ports default to whatever's in the environment (so `make box PORT=3007`
# followed by `make onboard` inside the container picks 3007 automatically),
# falling back to the canonical defaults. Flags still win over env vars.
PORT="${PORT:-3006}"
SHADOW_PORT="${SHADOW_PORT:-8020}"
SHADOW_NREPL_PORT="${SHADOW_NREPL_PORT:-9630}"

usage () {
    cat <<USAGE
Usage: $0 [--port N] [--shadow-port N] [--shadow-nrepl-port N]

Defaults are read from PORT / SHADOW_PORT / SHADOW_NREPL_PORT env vars
(set by 'make box PORT=...'), then the canonical 3006 / 8020 / 9630.

Note: --shadow-port currently only adjusts the docker port mapping. The
shadow-cljs.edn :dev-http key remains 8020 -- edit that file if you also
want shadow itself to bind a different port.
USAGE
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --port)              PORT="$2"; shift 2 ;;
        --shadow-port)       SHADOW_PORT="$2"; shift 2 ;;
        --shadow-nrepl-port) SHADOW_NREPL_PORT="$2"; shift 2 ;;
        -h|--help)           usage; exit 0 ;;
        *) echo "Unknown flag: $1" >&2; usage; exit 1 ;;
    esac
done

if [ -f "config.edn" ]; then
    echo "config.edn already exists. Remove it first (or 'make clean') if you want to reset."
    exit 1
fi

echo "Creating SQLite configuration on port ${PORT}..."
# :semsearch is only written when this onboard was launched with WITH_VEC=1
# (either `make box WITH_VEC=1 && make onboard` inside the container, or
# `WITH_VEC=1 ./scripts/onboard.sh` on the host). Without it, semsearch is
# disabled, which is what we want for users who don't need vector search.
if [ "${WITH_VEC:-0}" = "1" ]; then
    SEMSEARCH_LINE=$'\n :semsearch {:ollama-url "http://127.0.0.1:11434"\n             :ollama-model "nomic-embed-text"}'
else
    SEMSEARCH_LINE=""
fi
cat > config.edn <<EOF
{:port ${PORT}
 :dev? true
 :db {:dbtype "sqlite"
      :dbname "./rhizome.db"}
 :folders {:homefolder "./files/"}${SEMSEARCH_LINE}}
EOF

# docker/.env is auto-loaded by docker compose so ports flow into the YAML's
# \${PORT:-...} interpolations without the user retyping them. Recreated on
# every onboard run; `make clean` removes it.
echo "Writing docker/.env..."
cat > docker/.env <<EOF
PORT=${PORT}
SHADOW_PORT=${SHADOW_PORT}
SHADOW_NREPL_PORT=${SHADOW_NREPL_PORT}
EOF

echo "Creating directories..."
mkdir -p files/Documents/Tracked
mkdir -p files/Downloads/Tracked
mkdir -p files/Movies/Tracked
mkdir -p files/Music/Tracked
mkdir -p files/Pictures/Tracked/Preview/Lowres

case "$(uname -s)" in
    Darwin) VEC_EXT="dylib" ;;
    Linux)  VEC_EXT="so" ;;
    *)      VEC_EXT="" ;;
esac

VEC_LIB="./.sqlite-vec/vec0.$VEC_EXT"
SCHEMA_FILE="schema-sqlite.sql"

USE_VEC=0
if [ -n "$VEC_EXT" ] && [ -f "$VEC_LIB" ]; then
    if echo ".load $VEC_LIB" | sqlite3 ":memory:" >/dev/null 2>&1; then
        USE_VEC=1
        echo "Using sqlite-vec extension at $VEC_LIB"
    else
        echo "sqlite3 CLI was built without extension support — skipping vec0 virtual table. The JVM will create it at runtime (it bundles its own SQLite)."
    fi
else
    echo "sqlite-vec extension not found — skipping vec0 virtual table (vector search will be unavailable). Run 'make install-sqlite-vec' to enable it."
fi

load_schema () {
    local db="$1"
    if [ "$USE_VEC" = "1" ]; then
        { echo ".load $VEC_LIB"; cat "$SCHEMA_FILE"; } | sqlite3 "$db"
    else
        sed '/CREATE VIRTUAL TABLE IF NOT EXISTS items_vec/,/);/d' "$SCHEMA_FILE" | sqlite3 "$db"
    fi
}

echo "Creating SQLite database from schema..."
rm -f rhizome.db
load_schema rhizome.db

echo "Creating test SQLite database from schema..."
if [ -f "rhizome-test.db" ]; then
    echo "rhizome-test.db already exists. Refusing to overwrite."
else
    load_schema rhizome-test.db
fi

echo "Seeding database with contexts..."
./scripts/setup-demo-contexts.bb

echo "Seeding demo articles..."
./scripts/setup-demo-articles.bb

echo ""
echo "Done. Next:"
# Inside the dev container `npm install` is already done by entrypoint.sh on
# first entry, so don't tell the user to run it again. /.dockerenv is the
# standard "are we in a docker container" marker.
if [ ! -f /.dockerenv ]; then
    echo "  npm install      # one time"
fi
echo "  make start       # boots the JVM + shadow-cljs"
if [ -n "$VEC_EXT" ] && [ -f "$VEC_LIB" ]; then
    echo "  make backfill-embeddings   # embed the seeded articles"
fi
echo "Then visit http://localhost:${PORT}"

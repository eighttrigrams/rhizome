#!/bin/bash
set -e

echo "=== Rhizome Developer Onboarding ==="

if [ -f "config.edn" ]; then
    echo "config.edn already exists. Remove it first if you want to reset."
    exit 1
fi

echo "Creating SQLite configuration..."
cat > config.edn << 'EOF'
{:port 3006
 :dev? true
 :db {:dbtype "sqlite"
      :dbname "./rhizome.db"}
 :folders {:homefolder "./files/"}}
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
echo "  npm install      # one time"
echo "  make start       # boots the JVM + shadow-cljs"
if [ -n "$VEC_EXT" ] && [ -f "$VEC_LIB" ]; then
    echo "  make backfill-embeddings   # embed the seeded articles"
fi
echo "Then visit http://localhost:3006"

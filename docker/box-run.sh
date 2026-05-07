#!/bin/bash
set -e

cd /workspace/rhizome

if [ ! -f config.edn ]; then
  cat > config.edn <<'EOF'
{:port 3006
 :dev? true
 :db {:dbtype "sqlite"
      :dbname "./rhizome.db"}
 :folders {:homefolder "./files/"}}
EOF
fi

mkdir -p files/Documents/Tracked files/Downloads/Tracked \
         files/Movies/Tracked files/Music/Tracked \
         files/Pictures/Tracked/Preview/Lowres

if [ ! -f rhizome.db ]; then
  if [ -f /usr/local/lib/sqlite-vec/vec0.so ]; then
    { echo ".load /usr/local/lib/sqlite-vec/vec0"; cat schema-sqlite.sql; } | sqlite3 rhizome.db
  else
    sed '/CREATE VIRTUAL TABLE IF NOT EXISTS items_vec/,/);/d' schema-sqlite.sql | sqlite3 rhizome.db
  fi
  ./scripts/setup-demo-contexts.bb || true
  ./scripts/setup-demo-articles.bb || true
fi

if [ ! -d node_modules ] || [ -z "$(ls -A node_modules 2>/dev/null)" ]; then
  npm install --no-audit --no-fund
fi

npx shadow-cljs release app
exec clj -M:dev -m server

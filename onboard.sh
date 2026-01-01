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
mkdir -p files/Pictures/Tracked

echo "Creating SQLite database from schema..."
rm -f rhizome.db
sqlite3 rhizome.db < schema-sqlite.sql

echo "Seeding database with contexts..."
./setup-test-contexts.bb

echo "Installing npm dependencies..."
npm install

echo ""
echo "=== Setup Complete ==="
echo ""
echo "To start the application, run these in separate terminals:"
echo "  Terminal 1: ./dev.sh"
echo "  Terminal 2: npx shadow-cljs watch app"
echo ""
echo "Then visit http://localhost:3006"

#!/bin/bash
# Bring up the dev stack in the foreground: shadow-cljs watch is backgrounded
# (its stdout still streams to this TTY) and the JVM runs as the foreground
# process so Ctrl-C tears it down cleanly. Mirrors tracker's start.sh.
#
# We don't trap shadow-cljs on exit -- if the JVM dies (or you hit Ctrl-C)
# shadow keeps watching. `make stop` reads .shadow-cljs.pid and the shadow
# port to clean it up.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Refusal is informational, not an error. See note in scripts/e2e.sh.
./scripts/detect-ports.sh check PORT SHADOW_PORT || exit 0

# Record what's about to bind the ports so `make stop` (and detect-ports.sh
# check) can refuse a cross-env kill and print a useful diagnostic. On macOS,
# `lsof -ti:$PORT` from the host returns Docker's port-forward proxy PIDs,
# and killing those tears down the container's networking; the reverse misses
# the real PID entirely.
./scripts/write-lock.sh dev

PORT=$(./scripts/detect-ports.sh PORT)
SHADOW_PORT=$(./scripts/detect-ports.sh SHADOW_PORT)
export PORT SHADOW_PORT

echo "Starting shadow-cljs watch on :$SHADOW_PORT..."
npx shadow-cljs watch app &
echo $! > .shadow-cljs.pid

echo "Starting dev server on :$PORT..."
exec clj -M:dev -m server

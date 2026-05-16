#!/bin/bash
# Bring up the dev stack in the foreground: shadow-cljs watch is backgrounded
# (its stdout still streams to this TTY) and the JVM runs as the foreground
# process so Ctrl-C tears it down cleanly. Mirrors tracker's start.sh.
#
# We don't actively kill shadow-cljs on exit -- if the JVM dies (or you hit
# Ctrl-C) on the host, shadow keeps watching, and `make stop` is the way to
# tear it down. We *do* drop `.dev-server.lock` on exit when it's safe to:
# same env that wrote it AND no listeners left on either port. That covers
# the container case (where Ctrl-C also kills shadow because the container
# is going away) without ever clearing a lock under a still-running session.
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

# Only drop the lock if it belongs to *this* env and nothing is bound to
# either port any more. Shadow on the host typically survives the JVM's
# SIGINT, so the listener check naturally keeps the lock in place for
# `make stop` to handle. In a container, Ctrl-C tears down everything
# together; we poll briefly for shadow to release its port before deciding.
cleanup_lock() {
  trap - EXIT
  [ -f .dev-server.lock ] || return 0
  if [ -f /.dockerenv ]; then here=container; else here=host; fi
  lock_env=$(grep -E '^ENV=' .dev-server.lock | cut -d= -f2)
  [ "$lock_env" = "$here" ] || return 0
  for _ in 1 2 3 4 5 6; do
    listening=0
    lsof -nP -iTCP:"$PORT"        -sTCP:LISTEN >/dev/null 2>&1 && listening=1
    lsof -nP -iTCP:"$SHADOW_PORT" -sTCP:LISTEN >/dev/null 2>&1 && listening=1
    [ "$listening" = "0" ] && break
    sleep 0.5
  done
  [ "$listening" = "0" ] && rm -f .dev-server.lock .shadow-cljs.pid
}
# Plain EXIT only -- INT/TERM propagate to the JVM and we'll then return
# from the foreground `clj` below and fall through to EXIT naturally.
trap cleanup_lock EXIT

echo "Starting shadow-cljs watch on :$SHADOW_PORT..."
npx shadow-cljs watch app &
echo $! > .shadow-cljs.pid

echo "Starting dev server on :$PORT..."
# Don't `exec` -- that replaces the bash process so the EXIT trap never
# fires. Run as a foreground subprocess instead; bash regains control on
# JVM exit and the trap runs.
clj -M:dev -m server || true

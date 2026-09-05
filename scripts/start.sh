#!/bin/bash
# Bring up the dev stack in the foreground: the db-server goes up first (in the
# background, like shadow-cljs), shadow-cljs watch is backgrounded (its stdout
# still streams to this TTY) and the app JVM runs as the foreground process so
# Ctrl-C tears it down cleanly. Mirrors tracker's start.sh.
#
# The db-server is first because it owns the SQLite file and applies the
# schema, and because the app-server holds no datasource at all any more: it
# refuses to boot with nothing answering at its :db-url rather than coming up
# and failing at its first statement.
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

# Resolved before the lock is written so write-lock.sh can record it: the lock
# is what a human reads to see every port this session is holding.
DB_PORT="${DB_PORT:-$(./scripts/detect-ports.sh DB_PORT)}"
export DB_PORT

# Record what's about to bind the ports so `make stop` (and detect-ports.sh
# check) can refuse a cross-env kill and print a useful diagnostic. On macOS,
# `lsof -ti:$PORT` from the host returns Docker's port-forward proxy PIDs,
# and killing those tears down the container's networking; the reverse misses
# the real PID entirely.
DB_PORT="$DB_PORT" ./scripts/write-lock.sh dev

# Env wins (the Makefile/`make box` may already have propagated PORT and
# SHADOW_PORT in from the host shell). Only fall back to detect-ports.sh
# when nothing is set — matches the `?=` semantics in the Makefile.
PORT="${PORT:-$(./scripts/detect-ports.sh PORT)}"
SHADOW_PORT="${SHADOW_PORT:-$(./scripts/detect-ports.sh SHADOW_PORT)}"
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
  # .db-server.pid is deliberately NOT removed here. The db-server outlives
  # this session the way shadow-cljs does -- the next `make start` connects to
  # it instead of paying for a boot -- and `make stop` is what takes it down.
  [ "$listening" = "0" ] && rm -f .dev-server.lock .shadow-cljs.pid
}
# Plain EXIT only -- INT/TERM propagate to the JVM and we'll then return
# from the foreground `clj` below and fall through to EXIT naturally.
trap cleanup_lock EXIT

# --- the db-server ----------------------------------------------------------
# Wait on /health, not on the port: a bound port says jetty is listening, and
# /health says the database behind it answered a statement. Starting the app in
# front of a server that cannot reach its file is the case the health check
# exists for.
#
# An already-healthy one is CONNECTED TO rather than replaced. `make start-db`
# in another terminal is a supported way to run it, and two processes on one
# SQLite file is the thing this whole split exists to prevent -- so a second is
# never started. Anything else holding the port is refused instead of guessed
# at.
db_healthy() { curl -sf -m 2 "http://127.0.0.1:$DB_PORT/health" >/dev/null 2>&1; }

if db_healthy; then
  echo "db-server already answering on :$DB_PORT -- connecting to that one."
elif lsof -nP -iTCP:"$DB_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Refusing: :$DB_PORT is held by something that does not answer /health." >&2
  echo "That is either not a db-server or a broken one. Free the port and retry." >&2
  exit 1
else
  echo "Starting db-server on :$DB_PORT..."
  clj -M:dev -m db-server &
  # Structured like .dev-server.lock, and for its reason: this file lives in
  # the repo, which is bind-mounted, so a pid written inside a container is
  # visible on the host where it means a different process entirely. stop.sh
  # refuses to act across that line.
  #
  # PID is for humans and for that ownership question only -- it is NOT what
  # gets killed. `clj` is a bash wrapper that forks the JVM as a child, so $!
  # is the wrapper and it is gone by the time anything wants to stop the
  # server. Stopping happens by port, the way stop.sh has always stopped the
  # app and shadow-cljs (and why .shadow-cljs.pid says the same about itself).
  { echo "PID=$!"
    if [ -f /.dockerenv ]; then echo "ENV=container"; else echo "ENV=host"; fi
    echo "DB_PORT=$DB_PORT"
  } > .db-server.pid
  for _ in $(seq 1 60); do
    db_healthy && break
    sleep 0.5
  done
  if ! db_healthy; then
    echo "db-server did not answer /health on :$DB_PORT within 30s -- see its output above." >&2
    db_pids=$(lsof -nP -iTCP:"$DB_PORT" -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ' || true)
    # shellcheck disable=SC2086
    [ -n "$db_pids" ] && kill $db_pids 2>/dev/null || true
    rm -f .db-server.pid
    exit 1
  fi
  echo "db-server up on :$DB_PORT."
fi

echo "Starting shadow-cljs watch on :$SHADOW_PORT..."
npx shadow-cljs watch app &
echo $! > .shadow-cljs.pid

echo "Starting app server on :$PORT (db-server: http://127.0.0.1:$DB_PORT)..."
# Don't `exec` -- that replaces the bash process so the EXIT trap never
# fires. Run as a foreground subprocess instead; bash regains control on
# JVM exit and the trap runs.
clj -M:dev -m server || true

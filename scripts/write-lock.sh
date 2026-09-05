#!/bin/bash
# Write .dev-server.lock with structured info about what's currently
# holding the dev/e2e ports:
#   MODE=dev|e2e
#   ENV=host|container
#   HEADED=0|1            (e2e only)
#   PID=<owning shell pid> (best-effort, for human inspection)
#   DB_PORT=<port>        (when the caller exported one)
#
# Consumed by stop.sh (cross-env refusal) and useful for humans grepping
# the file. Plain key=value lines so callers can `grep -E '^MODE='` without
# parsing tooling.
set -e

MODE="${1:?usage: write-lock.sh dev|e2e [HEADED]}"
HEADED="${2:-}"

if [ -f /.dockerenv ]; then
  ENV=container
else
  ENV=host
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/.dev-server.lock"

{
  echo "MODE=$MODE"
  echo "ENV=$ENV"
  if [ "$MODE" = "e2e" ]; then
    echo "HEADED=${HEADED:-0}"
  fi
  echo "PID=$$"
  # The db-server's port, when the caller resolved one. Written for the same
  # reason as the rest: so a human (or a diagnostic) reading this file can see
  # every port the session is holding, the inner one included. It is recorded,
  # not acted on -- stop.sh stops a db-server by .db-server.pid, which says
  # which env started it. `if` rather than `&&` because this is the last
  # command in the group and `set -e` would take a false test for a failure.
  if [ -n "${DB_PORT:-}" ]; then
    echo "DB_PORT=$DB_PORT"
  fi
} > "$LOCK"

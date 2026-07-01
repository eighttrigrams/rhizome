#!/bin/bash
# Write .dev-server.lock with structured info about what's currently
# holding the dev/e2e ports:
#   MODE=dev|e2e
#   ENV=host|container
#   HEADED=0|1            (e2e only)
#   PID=<owning shell pid> (best-effort, for human inspection)
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
} > "$LOCK"

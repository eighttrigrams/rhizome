#!/bin/bash
# Usage:
#   detect-ports.sh PORT|SHADOW_PORT
#     Print the resolved port value, checking in order:
#       1. .envrc at the repo root (a `PORT=...` / `export PORT=...` line)
#       2. for PORT: the :port fallback in config.edn
#          for SHADOW_PORT: the :default in shadow-cljs.edn's :http :port
#       3. hardcoded final fallback (3006 / 9804)
#
#   detect-ports.sh check PORT [SHADOW_PORT ...]
#     Report-only. For each var, resolve and then lsof-check the port. If
#     anything is listening, print it and exit 1 without touching anything.
#     Used by `make start` (refuses to come up while an e2e run or another
#     dev server holds the same port) and `make e2e` (refuses to run while
#     a dev server / another e2e / docker compose forwarding holds it).
#     Models the `check` mode in the tracker project's stop.sh.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

resolve_from_envrc() {
  local var="$1"
  [ -f "$ROOT/.envrc" ] || return 1
  local val
  val=$(grep -E "^(export +)?$var=" "$ROOT/.envrc" 2>/dev/null \
        | tail -1 \
        | sed -E "s/^(export +)?$var=//; s/[[:space:]]*#.*//; s/^['\"]//; s/['\"]$//")
  [ -n "$val" ] && echo "$val"
}

resolve_from_config_edn() {
  [ -f "$ROOT/config.edn" ] || return 1
  # Match either a literal int (`:port 3006`) or aero's fallback in
  # `#or [#env PORT 3006]`; both end with the integer we want. Loose
  # anchor so an opening `{` on the line doesn't break the match.
  grep -E '(^|[[:space:]{]):port' "$ROOT/config.edn" \
    | head -1 \
    | grep -oE '[0-9]+' \
    | tail -1
}

resolve_from_shadow_cljs() {
  [ -f "$ROOT/shadow-cljs.edn" ] || return 1
  grep -oE ':default[[:space:]]+[0-9]+' "$ROOT/shadow-cljs.edn" \
    | head -1 \
    | grep -oE '[0-9]+'
}

resolve_port() {
  local var="$1" val=""
  case "$var" in
    PORT)
      val=$(resolve_from_envrc "$var") \
        || val=$(resolve_from_config_edn) \
        || val=3006
      ;;
    SHADOW_PORT)
      val=$(resolve_from_envrc "$var") \
        || val=$(resolve_from_shadow_cljs) \
        || val=9804
      ;;
    *)
      echo "unknown var: $var (expected PORT or SHADOW_PORT)" >&2
      return 2
      ;;
  esac
  echo "$val"
}

if [ "$1" = "check" ]; then
  shift
  [ $# -ge 1 ] || { echo "usage: detect-ports.sh check PORT|SHADOW_PORT [...]" >&2; exit 2; }

  # The dev and e2e tracks are mutually exclusive -- only one of them may
  # hold the ports at any time. The lockfile is claimed before any slow
  # work (shadow-cljs builds for e2e) so a concurrent caller is refused
  # before it starts, not when ports finally bind. When the lockfile is
  # present, that one line is all the user needs; we don't double up with
  # the lsof output (the lock already explains why those ports are busy).
  if [ -f "$ROOT/.dev-server.lock" ]; then
    mode=$(grep -E '^MODE=' "$ROOT/.dev-server.lock" | cut -d= -f2)
    env=$(grep -E '^ENV='  "$ROOT/.dev-server.lock" | cut -d= -f2)
    headed=$(grep -E '^HEADED=' "$ROOT/.dev-server.lock" | cut -d= -f2)
    pid=$(grep -E '^PID=' "$ROOT/.dev-server.lock" | cut -d= -f2)
    case "$mode" in
      dev) echo "Refusing: dev server is running (env=$env, pid=$pid). 'make stop' first." >&2 ;;
      e2e) echo "Refusing: e2e run is in flight (env=$env, headed=${headed:-0}, pid=$pid). Wait for it to finish, or remove .dev-server.lock if you know it's stale." >&2 ;;
      *)   echo "Refusing: .dev-server.lock present but unrecognised (mode=$mode). Inspect it and delete if stale." >&2 ;;
    esac
    exit 1
  fi

  blocked=0
  for var in "$@"; do
    port=$(resolve_port "$var")
    pids=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ' | sed 's/ $//' || true)
    if [ -n "$pids" ]; then
      echo "Something is listening on :$port (pid $pids; resolved from $var)." >&2
      echo "Run 'make stop' (or shut down the other process) and re-try." >&2
      blocked=1
    fi
  done
  exit "$blocked"
fi

VAR="${1:?usage: detect-ports.sh PORT|SHADOW_PORT  |  detect-ports.sh check PORT [...]}"
resolve_port "$VAR"

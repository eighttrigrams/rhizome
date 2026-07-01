#!/bin/bash
# Usage:
#   detect-ports.sh PORT|SHADOW_PORT
#     Print the resolved port value, checking in order:
#       1. for PORT: the :port fallback in config.edn
#          for SHADOW_PORT: the :default in shadow-cljs.edn's :http :port
#       2. hardcoded final fallback (3140 / 9804)
#
#     Note: this script does NOT read .envrc. Env-var overrides are the
#     caller's responsibility — if PORT/SHADOW_PORT are exported (by
#     direnv, manual export, the Makefile chain, CI, etc.) the Makefile's
#     `?=` consumes them and never invokes this script. When the env is
#     empty we fall straight through to the in-repo config defaults; a
#     dropped-but-unloaded .envrc is treated as "not set."
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

resolve_from_config_edn() {
  [ -f "$ROOT/config.edn" ] || return 1
  # Match either a literal int (`:port 3140`) or aero's fallback in
  # `#or [#env PORT 3140]`; both end with the integer we want. Loose
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
      val=$(resolve_from_config_edn) || val=3140
      ;;
    SHADOW_PORT)
      val=$(resolve_from_shadow_cljs) || val=9804
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

  # Cache `docker ps` once per check so we can tell, port by port, whether
  # a running container is publishing it. When that's the case `make stop`
  # inside the container won't free the host bind -- Docker keeps the
  # forwarder up until the container itself stops -- so we steer the user
  # at `docker stop` instead of the generic message. `|| true` swallows the
  # case where docker isn't installed / the daemon isn't running.
  docker_ports=$(docker ps --format '{{.Names}}'$'\t''{{.Ports}}' 2>/dev/null || true)

  # First pass: collect blocked (port,var,container) triples without
  # printing yet, so we can group multiple ports held by the same
  # container into a single message instead of repeating the same
  # "stop the container" paragraph per port.
  blocked_rows=""
  containers_seen=""
  for var in "$@"; do
    port=$(resolve_port "$var")
    pids=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ' | sed 's/ $//' || true)
    [ -n "$pids" ] || continue
    container=$(echo "$docker_ports" | awk -v p=":$port->" '$0 ~ p {print $1; exit}')
    blocked_rows="$blocked_rows$port|$var|$pids|$container"$'\n'
    if [ -n "$container" ]; then
      case " $containers_seen " in *" $container "*) ;; *) containers_seen="$containers_seen $container" ;; esac
    fi
  done

  if [ -z "$blocked_rows" ]; then
    exit 0
  fi

  # Output design: lead with the diagnosis (one short sentence), explain
  # why the usual fix won't work, then put the actual command on its own
  # indented line so the eye lands on it immediately. Blank lines separate
  # independent failures so they don't visually merge into a wall of text;
  # a leading + trailing blank frames the whole report so it doesn't
  # collide with the user's prompt or the next caller's output.
  echo >&2
  first=1
  for container in $containers_seen; do
    [ $first -eq 1 ] || echo >&2
    first=0
    ports_for_container=$(echo "$blocked_rows" | awk -F'|' -v c="$container" '$4 == c {printf ":%s ", $1}' | sed 's/ $//' | tr ' ' ',' | sed 's/,/, /g')
    echo "Container '$container' is publishing $ports_for_container." >&2
    echo "'make stop' inside it won't free these -- stop the container itself:" >&2
    echo >&2
    echo "    docker stop $container" >&2
  done

  if echo "$blocked_rows" | awk -F'|' '$4 == "" && NF >= 3 {found=1} END {exit !found}'; then
    [ $first -eq 1 ] || echo >&2
    echo "$blocked_rows" | awk -F'|' '$4 == "" && NF >= 3 {
      printf ":%s is held by pid %s.\n", $1, $3
    }' >&2
    echo "Run 'make stop' (or shut down the other process) and re-try." >&2
  fi
  echo >&2

  exit 1
fi

VAR="${1:?usage: detect-ports.sh PORT|SHADOW_PORT  |  detect-ports.sh check PORT [...]}"
resolve_port "$VAR"

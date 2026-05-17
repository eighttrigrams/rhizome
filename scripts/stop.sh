#!/bin/bash
# Tear down whichever side currently owns the dev/e2e ports, refusing if
# the lock says they were started in a different environment (host vs.
# container). Also clears `.shadow-cljs.pid` and the lockfile.
#
# Cross-env refusal matters on macOS: `lsof -ti:$PORT` from the host
# returns Docker's port-forward proxy PIDs and killing those tears down
# the container's networking. The reverse misses the real PID entirely.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

LOCK="$ROOT/.dev-server.lock"

# Env wins; only fall back to detect-ports.sh when nothing is set. Stop
# must target the *same* port the matching start.sh actually bound to,
# and that one honors $PORT/$SHADOW_PORT first too.
PORT="${PORT:-$(./scripts/detect-ports.sh PORT)}"
SHADOW_PORT="${SHADOW_PORT:-$(./scripts/detect-ports.sh SHADOW_PORT)}"

if [ -f /.dockerenv ]; then here=container; else here=host; fi

listening=0
for p in "$PORT" "$SHADOW_PORT"; do
  lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 && listening=1
done

# Parse the lock if present, even when nothing is bound -- an in-flight e2e
# run claims the lock before its shadow-cljs build starts and there are
# briefly no listeners.
lock_mode=""
lock_env=""
lock_headed=""
lock_pid=""
if [ -f "$LOCK" ]; then
  lock_mode=$(grep   -E '^MODE='   "$LOCK" | cut -d= -f2)
  lock_env=$(grep    -E '^ENV='    "$LOCK" | cut -d= -f2)
  lock_headed=$(grep -E '^HEADED=' "$LOCK" | cut -d= -f2)
  lock_pid=$(grep    -E '^PID='    "$LOCK" | cut -d= -f2)
fi

if [ "$listening" -eq 0 ] && [ -z "$lock_mode" ]; then
  echo "Nothing to stop."
  rm -f "$LOCK" .shadow-cljs.pid
  exit 0
fi

# The four refusal paths below all exit 0 -- they're informational, not
# errors, and a non-zero exit would surface as `make: *** [stop] Error 1`
# under what is really a clean no-op from the user's point of view.

if [ "$listening" -eq 0 ] && [ -n "$lock_mode" ]; then
  # Lockfile present but no listeners.  e2e mode is ambiguous (the run may
  # just be ramping up between claiming the lock and binding the port) so
  # don't auto-clean.  dev mode with no listeners is unambiguously stale
  # when the lock belongs to *this* env -- clear it and say so.  Cross-env
  # stale locks have to be cleared from the env that wrote them.
  case "$lock_mode" in
    e2e)
      echo "An e2e run is ramping up (env=$lock_env, headed=${lock_headed:-0}, pid=$lock_pid). Wait for it, or 'rm .dev-server.lock' if stale."
      ;;
    dev)
      if [ "$lock_env" = "$here" ]; then
        rm -f "$LOCK" .shadow-cljs.pid
        echo "Lock claimed a dev server (env=$lock_env, pid=$lock_pid) but nothing was listening. Cleared .dev-server.lock${lock_pid:+ and .shadow-cljs.pid}."
      else
        echo "Lock claims a dev server (env=$lock_env, pid=$lock_pid) but nothing is listening on this side. Run 'make stop' from the $lock_env (or 'rm .dev-server.lock' there) if it's stale."
      fi
      ;;
    *)
      echo "Unrecognised lock mode '$lock_mode'. Inspect .dev-server.lock and delete if stale."
      ;;
  esac
  exit 0
fi

if [ -z "$lock_mode" ]; then
  if [ "$here" = "host" ]; then
    echo "Ports are held but .dev-server.lock is missing -- most likely Docker's port-forwarder for a running container. Exit the container (or 'docker compose down') and try again."
  else
    echo "Ports are held but .dev-server.lock is missing -- refusing to kill an unknown process. Investigate manually."
  fi
  exit 0
fi

if [ "$lock_env" != "$here" ]; then
  echo "$lock_mode session was started in '$lock_env'; run 'make stop' there (you are on the $here)."
  exit 0
fi

# Don't yank an e2e run out from under playwright. e2e.sh's trap drops the
# lock cleanly on EXIT/INT/TERM, so a manual `make stop` mid-run would
# leave half-killed processes behind and produce confusing test reports.
# The user can `rm .dev-server.lock && make stop` if they really need to.
if [ "$lock_mode" = "e2e" ]; then
  echo "Refusing: an e2e run is in flight (env=$lock_env, headed=${lock_headed:-0}, pid=$lock_pid). Wait for it, or 'rm .dev-server.lock && make stop' if stuck."
  exit 0
fi

echo "Stopping $lock_mode session (env=$lock_env${lock_headed:+, headed=$lock_headed}, pid=$lock_pid)..."
for p in "$PORT" "$SHADOW_PORT"; do
  pids=$(lsof -nP -iTCP:"$p" -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ' | sed 's/ $//' || true)
  if [ -n "$pids" ]; then
    echo "  killing $pids on :$p"
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
  fi
done

rm -f "$LOCK" .shadow-cljs.pid

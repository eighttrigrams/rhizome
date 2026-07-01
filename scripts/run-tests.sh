#!/bin/bash
# Decide whether to run :vector tests based on the same signal the app uses:
# presence of :semsearch :vec-path resolved against config.edn AND the dylib
# actually on disk. Drop :semsearch from config.edn to skip vector tests.
# config.edn writes :vec-path #or [#env VEC_PATH "..."] (see onboard.sh) so
# we honor $VEC_PATH first (set by the Dockerfile in containers), otherwise
# fall back to the default literal baked into config.edn.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="$ROOT/config.edn"

case "$(uname -s)" in
  Darwin) EXT=dylib ;;
  *)      EXT=so    ;;
esac

# Pull :semsearch :vec-path out of config.edn with a small bb/clj-free
# parse. Multi-line: :semsearch can span lines, so flatten first.
# Mirrors aero #or [#env VEC_PATH "default"]: $VEC_PATH wins, otherwise the
# first quoted string after :vec-path is the default. The `grep -oE '"[^"]*"'
# | head -1` form handles both `:vec-path "..."` and the #or form.
VEC_PATH_ENV="${VEC_PATH:-}"
VEC_PATH=""
if [ -f "$CONFIG" ]; then
  VEC_PATH=$(tr '\n' ' ' < "$CONFIG" \
    | grep -oE ':semsearch[[:space:]]*\{[^}]*\}' \
    | grep -oE ':vec-path[^}]*' \
    | grep -oE '"[^"]*"' \
    | head -1 \
    | sed -E 's/"([^"]*)"/\1/')
fi
[ -n "$VEC_PATH_ENV" ] && VEC_PATH="$VEC_PATH_ENV"

if [ -n "$VEC_PATH" ] && [ -f "${VEC_PATH}.${EXT}" ]; then
  clj -M:test \
    && echo "tests passed (including :vector tests; sqlite-vec found at ${VEC_PATH}.${EXT})"
else
  if [ -z "$VEC_PATH" ]; then
    echo ":semsearch :vec-path not set in config.edn; excluding ^:vector tests"
  else
    echo "sqlite-vec not at ${VEC_PATH}.${EXT}; excluding ^:vector tests"
  fi
  clj -M:test --exclude :vector \
    && echo "tests passed (WITHOUT :vector tests)"
fi

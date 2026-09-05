#!/bin/bash
# Decide whether to run :vector tests based on the same signal the db-server
# uses: presence of :db-server :vec-path resolved against config.edn AND the
# dylib actually on disk. Drop :vec-path from the :db-server block to skip
# vector tests.
#
# The key sat under :semsearch until the app/db split -- loading the extension
# is the database's business, and :semsearch keeps the app-side embedder's
# :ollama-url / :ollama-model. This grep had to follow it: pointed at the old
# key it finds nothing, and the ^:vector tests stop running with nothing to
# see anywhere. config.edn writes :vec-path #or [#env VEC_PATH "..."] (see
# onboard.sh) so we honor $VEC_PATH first (set by the Dockerfile in
# containers), otherwise fall back to the default literal baked into it.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="$ROOT/config.edn"

case "$(uname -s)" in
  Darwin) EXT=dylib ;;
  *)      EXT=so    ;;
esac

# Pull :db-server :vec-path out of config.edn with a small bb/clj-free
# parse. Multi-line: the block spans lines, so flatten first.
# Mirrors aero #or [#env VEC_PATH "default"]: $VEC_PATH wins, otherwise the
# first quoted string after :vec-path is the default. The `grep -oE '"[^"]*"'
# | head -1` form handles both `:vec-path "..."` and the #or form.
VEC_PATH_ENV="${VEC_PATH:-}"
VEC_PATH=""
if [ -f "$CONFIG" ]; then
  VEC_PATH=$(tr '\n' ' ' < "$CONFIG" \
    | grep -oE ':db-server[[:space:]]*\{[^}]*\}' \
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
    echo ":db-server :vec-path not set in config.edn; excluding ^:vector tests"
  else
    echo "sqlite-vec not at ${VEC_PATH}.${EXT}; excluding ^:vector tests"
  fi
  clj -M:test --exclude :vector \
    && echo "tests passed (WITHOUT :vector tests)"
fi

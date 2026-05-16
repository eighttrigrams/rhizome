#!/bin/bash
# Decide whether to run :vector tests based on the same signal the app uses:
# presence of :semsearch :vec-path in config.edn AND the dylib actually on
# disk. Drop :semsearch from config.edn to skip vector tests; no env vars
# involved.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="$ROOT/config.edn"

case "$(uname -s)" in
  Darwin) EXT=dylib ;;
  *)      EXT=so    ;;
esac

# Pull :semsearch :vec-path out of config.edn with a small bb/clj-free
# parse. Multi-line: :semsearch can span lines, so flatten first.
VEC_PATH=""
if [ -f "$CONFIG" ]; then
  VEC_PATH=$(tr '\n' ' ' < "$CONFIG" \
    | grep -oE ':semsearch[[:space:]]*\{[^}]*\}' \
    | grep -oE ':vec-path[[:space:]]*"[^"]*"' \
    | sed -E 's/.*"([^"]*)"/\1/')
fi

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

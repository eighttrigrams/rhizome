#!/bin/bash
# Orchestrate `make e2e`: refuse if a dev session or another e2e run is in
# flight, claim the dev-server.lock *before* the slow shadow-cljs build (so
# nobody else races in during that window), build the release bundle, then
# hand off to playwright. The lock is removed on exit no matter how we got
# here (success, failure, Ctrl-C).
#
# Env knobs (mirrors tracker's Makefile):
#   HEADED=1     show the browser (default headless)
#   NO_BUILD=1   skip `shadow-cljs release app` -- reuses the previously
#                built main.js. Fine when no cljs changed since the last
#                run; cuts iteration from ~30s to ~5s.
#   T="..."      pass `-g <T>` to playwright; filter to scenarios whose
#                name matches the substring/regex.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

HEADED="${HEADED:-0}"
NO_BUILD="${NO_BUILD:-0}"
T="${T:-}"

# `box` (the plain dev container) deliberately ships without chromium to
# keep the image small. Detect that case before doing anything expensive
# and steer the user at the image that has the browser stack -- otherwise
# they hit a generic playwright error after a 10s shadow-cljs build.
if [ -f /.dockerenv ] && [ ! -x /usr/bin/chromium ]; then
  # Refusal is informational, not an error -- exit 0 so Make doesn't tack
  # on a `*** [e2e] Error 1` tail. Same pattern as the lock/port refusal.
  echo "Refusing: this container has no chromium installed."
  echo
  echo "  You appear to be inside the 'box' dev shell, which intentionally"
  echo "  ships without the Playwright/Chromium stack. Run e2e either:"
  echo "    - on the host       (npx playwright install chromium once, then 'make e2e'), or"
  echo "    - inside 'yolo'     (exit this shell, then 'make yolo' on the host)."
  exit 0
fi

# Refusal is informational, not an error. Bail with exit 0 so callers
# (and Make) don't get a "*** [e2e] Error 1" tail under what is, from
# the user's point of view, a clean no-op.
./scripts/detect-ports.sh check PORT SHADOW_PORT || exit 0

./scripts/write-lock.sh e2e "$HEADED"
trap 'rm -f .dev-server.lock' EXIT INT TERM

if [ "$NO_BUILD" = "1" ]; then
  echo "Skipping shadow-cljs release build (NO_BUILD=1)."
else
  echo "Building shadow-cljs release bundle (HEADED=$HEADED)..."
  npx shadow-cljs release app
fi

echo "Running playwright${T:+ (filter: $T)}..."
npx bddgen --config test/playwright.config.ts
if [ -n "$T" ]; then
  HEADED="$HEADED" npx playwright test -c test/playwright.config.ts -g "$T"
else
  HEADED="$HEADED" npx playwright test -c test/playwright.config.ts
fi

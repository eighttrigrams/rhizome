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
# STRICT=1 turns refusals into hard failures. `make deploy` sets it so a
# missing chromium or held lock fails the deploy chain instead of being
# silently skipped. Interactive `make e2e` leaves it unset and keeps the
# friendly exit-0 refusals.
STRICT="${STRICT:-0}"
refuse_exit=0
[ "$STRICT" = "1" ] && refuse_exit=1

# `box` (the plain dev container) deliberately ships without chromium to
# keep the image small. Detect that case before doing anything expensive
# and steer the user at the host, which is where the browser stack lives --
# otherwise they hit a generic playwright error after a 10s shadow-cljs build.
if [ -f /.dockerenv ] && [ ! -x /usr/bin/chromium ]; then
  # Refusal is informational, not an error -- exit 0 so Make doesn't tack
  # on a `*** [e2e] Error 1` tail. Same pattern as the lock/port refusal.
  # STRICT=1 (e.g. `make deploy`) flips this to a hard failure.
  echo "Refusing: this container has no chromium installed."
  echo
  echo "  You appear to be inside the 'box' dev shell, which intentionally"
  echo "  ships without the Playwright/Chromium stack."
  echo
  echo "  Run e2e on the host instead: exit this shell, then"
  echo "    npx playwright install chromium     # first time only"
  echo "    make e2e"
  exit "$refuse_exit"
fi

# Refusal is informational, not an error. Bail with exit 0 so callers
# (and Make) don't get a "*** [e2e] Error 1" tail under what is, from
# the user's point of view, a clean no-op. STRICT=1 makes this a hard
# failure so `make deploy` can't silently skip e2e on a held lock.
# DB_PORT is in the check here and NOT in `make start`'s, and the asymmetry is
# the point. `make start` connects to a healthy db-server if it finds one --
# that is the sharing this split allows. An e2e run must not: the one it would
# find is pointed at ./rhizome.db, and this suite would then run its scenarios
# against the developer's own database and delete rows out of it. So anything
# on that port is a refusal, and this run brings up its own.
./scripts/detect-ports.sh check PORT SHADOW_PORT DB_PORT || exit "$refuse_exit"

DB_PORT="${DB_PORT:-$(./scripts/detect-ports.sh DB_PORT)}"
export DB_PORT

DB_PORT="$DB_PORT" ./scripts/write-lock.sh e2e "$HEADED"
trap 'rm -f .dev-server.lock' EXIT INT TERM

# --- the db-server for this run ---------------------------------------------
# The app-server holds no datasource since the split and refuses to boot with
# nothing behind it, so playwright's webServer would time out with a message
# about a port. This is the process it talks to, and it is pointed at the e2e
# database by DB_PATH: config.edn writes `:db-path #or [#env DB_PATH "./rhizome.db"]`,
# so exporting the variable is the whole of it -- no second config file, and
# the app inherits the variable and has no use for it.
#
# It applies the schema as it opens the file, which is what the app-server used
# to do at its own startup. A fresh ./test/rhizome-e2e.db is therefore still
# created and populated by this line and not by the JVM playwright spawns.
export DB_PATH="./test/rhizome-e2e.db"
echo "Starting db-server for e2e on :$DB_PORT (db: $DB_PATH)..."
clj -M:e2e -m db-server &
# By port, not by $!: `clj` is a bash wrapper that forks the JVM as a child, so
# $! is the wrapper and it is gone before this trap ever runs. Whatever holds
# DB_PORT is this run's -- the check above refused to start if anything already
# did. SIGTERM, so the shutdown hook rolls back anything open rather than
# leaving SQLite's write lock behind.
stop_e2e_db_server() {
  pids=$(lsof -nP -iTCP:"$DB_PORT" -sTCP:LISTEN -t 2>/dev/null | tr '\n' ' ' || true)
  # shellcheck disable=SC2086
  [ -n "$pids" ] && kill $pids 2>/dev/null || true
}
# Replaces the trap above: same lock cleanup, plus this server.
trap 'stop_e2e_db_server; rm -f .dev-server.lock' EXIT INT TERM

for _ in $(seq 1 60); do
  curl -sf -m 2 "http://127.0.0.1:$DB_PORT/health" >/dev/null 2>&1 && break
  sleep 0.5
done
if ! curl -sf -m 2 "http://127.0.0.1:$DB_PORT/health" >/dev/null 2>&1; then
  echo "db-server did not answer /health on :$DB_PORT within 30s -- see its output above." >&2
  exit 1
fi
echo "db-server up on :$DB_PORT."


if [ "$NO_BUILD" = "1" ]; then
  echo "Skipping shadow-cljs release build (NO_BUILD=1)."
else
  echo "Building shadow-cljs release bundle (HEADED=$HEADED)..."
  npx shadow-cljs release app
fi

echo "Running playwright${T:+ (filter: $T)}..."
# Playwright caches the compiled form of every test file it loads, and by default
# that cache sits in the system temp dir keyed only by uid -- one cache shared
# with every other project on the machine, outside the checkout and invisible to
# `make clean`. It went stale once and cost an afternoon: it served a four-day-old
# copy of provenance.feature.spec.js, so playwright ran tests at line numbers the
# freshly generated module knew nothing about, and all six scenarios in that
# feature died at fixture setup with
#     bddTestData not found for test: ...provenance.feature.spec.js:108
# while the file on disk had that test at line 39. Nothing in the repo was wrong,
# and nothing in the repo could be changed to fix it.
#
# The specs under test/.features-gen are regenerated by the bddgen line below on
# every run, so a cache of them buys close to nothing. Keep it inside the checkout
# (where `make clean` can reach it) and drop it first: a cold transform costs
# about a second.
export PWTEST_CACHE_DIR="$ROOT/.playwright-cache"
rm -rf "$PWTEST_CACHE_DIR"
npx bddgen --config test/playwright.config.ts
if [ -n "$T" ]; then
  HEADED="$HEADED" npx playwright test -c test/playwright.config.ts -g "$T"
else
  HEADED="$HEADED" npx playwright test -c test/playwright.config.ts
fi

# Ports: env wins (`?=` skips the $(shell ...) if PORT/SHADOW_PORT are
# already exported — by direnv, the user's shell, the Makefile chain, CI,
# etc.). Otherwise scripts/detect-ports.sh resolves them from config.edn /
# shadow-cljs.edn, with a hardcoded final fallback. .envrc is NOT parsed;
# if you want it to take effect you need direnv (or `export` it yourself)
# before invoking make. Same values flow into host-side `make start`/`stop`
# and the generated docker overlay so both sides agree.
PORT        ?= $(shell ./scripts/detect-ports.sh PORT)
SHADOW_PORT ?= $(shell ./scripts/detect-ports.sh SHADOW_PORT)
# The db-server's port, same `?=` rule: an exported DB_PORT wins, otherwise it
# comes out of config.edn's :db-server section (final fallback 3141, which is
# db-server/default-port). Deliberately absent from COMPOSE_ENV below -- the
# seam is internal, the db-server binds loopback inside the container, and its
# port is not published to the host.
DB_PORT     ?= $(shell ./scripts/detect-ports.sh DB_PORT)
# DEPLOY_TARGET has no default and is deliberately NOT read from the
# environment: `deploy` requires it to be passed on the command line
# (make deploy DEPLOY_TARGET=/path) so a stray exported value can't
# silently redirect where the jar ships. Enforced by the origin check
# below (scoped to the deploy goal so other targets are unaffected).
ifeq (deploy,$(filter deploy,$(MAKECMDGOALS)))
ifneq (command line,$(origin DEPLOY_TARGET))
$(error DEPLOY_TARGET is required and must be passed on the command line: make deploy DEPLOY_TARGET=/path/to/dir)
endif
endif

.PHONY: start start-db stop test e2e deploy install-sqlite-vec box backfill-embeddings clean

onboard:
	./scripts/onboard.sh

clean:
	rm -f config.edn
	rm -f rhizome.db
	rm -f test/rhizome-e2e.db
	rm -f docker/compose.ports.yml
	rm -f *.db-journal *.db-wal *.db-shm
	rm -f test/*.db-journal test/*.db-wal test/*.db-shm
	rm -rf .playwright-cache

# When WITH_VEC=1, also activate the `vec` compose profile so the Ollama
# sidecar starts. Otherwise it stays absent and devs who don't need semsearch
# never pay the 3 GB pull cost.
COMPOSE_VEC = $(if $(filter 1,$(WITH_VEC)),COMPOSE_PROFILES=vec,)

# ollama_models is declared `external: true` in docker-compose.yml so the
# embedding model is downloaded once per machine and shared, rather than once
# per compose project (see the volumes block there). External disables
# compose's auto-creation, so the volume has to exist before the sidecar comes
# up. `docker volume create` is idempotent -- a no-op when it already exists --
# and is only worth running when the `vec` profile will actually start ollama.
ENSURE_OLLAMA_VOLUME = $(if $(filter 1,$(WITH_VEC)),docker volume create rhizome_ollama_models >/dev/null &&,)

# Always layer docker-compose.yml with the generated compose.ports.yml so
# the host bindings come from .envrc / config.edn / shadow-cljs.edn rather
# than YAML fallbacks. COMPOSE_FILE uses ':' as separator (compose convention).
#
# There is deliberately no per-machine override layer here. A file named in
# COMPOSE_FILE that does not exist is a hard error from compose, not a skipped
# file, so naming an optional one would break `make box` in every checkout
# that does not happen to have it.
COMPOSE_FILES = COMPOSE_FILE=docker-compose.yml:compose.ports.yml

# Compose project name is derived from this checkout's directory name so
# two sibling clones get separate volumes (box_m2_cache, box_npm_cache, ...)
# and container names. Compose project names must match [a-z0-9][a-z0-9_-]*
# -- lowercase the basename and replace `.` with `-` (covers names like
# "rhizome.alt").
COMPOSE_PROJECT_NAME := $(subst .,-,$(shell echo $(notdir $(CURDIR)) | tr '[:upper:]' '[:lower:]'))

# Same PORT/SHADOW_PORT also flow into the container as env vars; aero in
# config.clj and shadow-cljs honor them via #env so the JVM/shadow bind to
# the host-bound port. Note: the container does not parse .envrc either —
# the Makefile here is the single point that exports these vars across the
# docker boundary.
COMPOSE_ENV = PORT=$(PORT) SHADOW_PORT=$(SHADOW_PORT) WITH_VEC=$(WITH_VEC) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) $(COMPOSE_VEC) $(COMPOSE_FILES)

# detect-ports.sh check, write-compose-ports.sh and the docker invocation
# are chained in a single shell so a refusal (exit 0 via ||) actually skips
# the rest of the recipe. Make runs each recipe line in its own shell, so
# the previous two-line form would exit 0 on the guard and then merrily
# build/run docker anyway.
#
# --name gives the container a fixed, memorable name rather than compose's
# `rhizome-box-run-<random hex>`. Two knock-ons, both wanted: the blocked-port
# message in detect-ports.sh tells you to `docker stop <container>`, which is
# now typeable; and a second concurrent `make box` fails immediately with
# "container name is already in use" instead of quietly starting a second box.
# `--rm` still removes it on exit, so the name frees up for the next run.
box:
	@./scripts/detect-ports.sh check PORT SHADOW_PORT || exit 0; \
	./scripts/write-compose-ports.sh $(PORT) $(SHADOW_PORT) && \
	$(ENSURE_OLLAMA_VOLUME) \
	cd docker && $(COMPOSE_ENV) docker compose build box && $(COMPOSE_ENV) docker compose run --rm --service-ports --name rhizome-box box

install-sqlite-vec:
	@./scripts/install-sqlite-vec.sh

backfill-embeddings:
	@curl -sS -X POST http://127.0.0.1:$(PORT)/api/backfill/embeddings \
	  -H 'Content-Type: application/json' \
	  -d '{"reason":"make backfill-embeddings"}'
	@echo

start:
	@./scripts/start.sh

# The db-server on its own, in the foreground, so Ctrl-C stops it. `make start`
# afterwards finds it answering /health and connects to that one instead of
# starting a second -- two processes on one SQLite file is what the split
# exists to prevent. The port check refuses when a dev session already holds
# the lock, which is the case where a db-server is already running for you.
start-db:
	@./scripts/detect-ports.sh check DB_PORT || exit 0; \
	DB_PORT=$(DB_PORT) clj -M:dev -m db-server

stop:
	@./scripts/stop.sh

test:
	@./scripts/run-tests.sh

HEADED   ?= 0
NO_BUILD ?=
T        ?=
# Usage:
#   make e2e                              full headless run
#   make e2e HEADED=1                     show the browser
#   make e2e T="creates a context"        playwright -g filter (substring/regex)
#   make e2e NO_BUILD=1                   skip shadow-cljs release build
#                                         (reuses the cached main.js -- fine
#                                         when no cljs changed since last run)
#
# scripts/e2e.sh claims .dev-server.lock before the slow shadow-cljs release
# build so a concurrent `make start` gets refused immediately rather than
# racing the JVM after the build completes. Lock is dropped on exit
# (success / failure / Ctrl-C).
# STRICT is propagated to scripts/e2e.sh. Defaults to 0 for interactive
# `make e2e` (refusals stay friendly: exit 0, no `*** [e2e] Error 1` tail).
# `deploy` flips it on via a target-specific override so a held lock or
# missing chromium hard-fails the deploy chain instead of silently
# skipping e2e and going on to ship a jar.
STRICT ?= 0
e2e:
	@HEADED=$(HEADED) NO_BUILD=$(NO_BUILD) T='$(T)' STRICT=$(STRICT) ./scripts/e2e.sh

deploy: STRICT := 1
deploy: test e2e
	npm i
	npx shadow-cljs release app
	clj -T:build jar
	@if [ ! -d "$(DEPLOY_TARGET)" ]; then echo "deploy target not found: $(DEPLOY_TARGET)"; exit 1; fi
	@if [ -f "$(DEPLOY_TARGET)/server.jar" ]; then cp "$(DEPLOY_TARGET)/server.jar" "$(DEPLOY_TARGET)/server.jar.bkp"; fi
	cp server.jar "$(DEPLOY_TARGET)/server.jar"
	cp schema-sqlite.sql "$(DEPLOY_TARGET)/schema-sqlite.sql"
	rm server.jar
	@echo "deployed server.jar + schema-sqlite.sql -> $(DEPLOY_TARGET)"

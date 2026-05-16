# Ports are resolved by scripts/detect-ports.sh: .envrc wins, then config.edn
# / shadow-cljs.edn, then a hardcoded final fallback. Same values are used
# for host-side `make start`/`stop` and for the generated docker overlay so
# both sides agree without anyone retyping a port.
PORT        ?= $(shell ./scripts/detect-ports.sh PORT)
SHADOW_PORT ?= $(shell ./scripts/detect-ports.sh SHADOW_PORT)
DEPLOY_TARGET ?= $(HOME)/Applications/rhizome

.PHONY: start stop test e2e deploy install-sqlite-vec yolo box backfill-embeddings clean

onboard:
	./scripts/onboard.sh

clean:
	rm -f config.edn
	rm -f rhizome.db
	rm -f test/rhizome-e2e.db
	rm -f docker/compose.ports.yml
	rm -f *.db-journal *.db-wal *.db-shm
	rm -f test/*.db-journal test/*.db-wal test/*.db-shm

# When WITH_VEC=1, also activate the `vec` compose profile so the Ollama
# sidecar starts. Otherwise it stays absent and devs who don't need semsearch
# never pay the 3 GB pull cost.
COMPOSE_VEC = $(if $(filter 1,$(WITH_VEC)),COMPOSE_PROFILES=vec,)

# Always layer docker-compose.yml with the generated compose.ports.yml so
# the host bindings come from .envrc / config.edn / shadow-cljs.edn rather
# than YAML fallbacks. COMPOSE_FILE uses ':' as separator (compose convention).
COMPOSE_FILES = COMPOSE_FILE=docker-compose.yml:compose.ports.yml

# Compose project name is derived from this checkout's directory name so
# two sibling clones get separate volumes (m2_cache, npm_cache, ...) and
# container names. Compose project names must match [a-z0-9][a-z0-9_-]*
# -- lowercase the basename and replace `.` with `-` (covers names like
# "rhizome.alt"). Override at invocation time to pin a custom name.
COMPOSE_PROJECT_NAME ?= $(subst .,-,$(shell echo $(notdir $(CURDIR)) | tr '[:upper:]' '[:lower:]'))

# Same PORT/SHADOW_PORT also flow into the container as env vars; aero in
# config.clj and shadow-cljs honor them via #env so the JVM/shadow bind to
# the host-bound port without us having to add direnv inside the container.
COMPOSE_ENV = PORT=$(PORT) SHADOW_PORT=$(SHADOW_PORT) WITH_VEC=$(WITH_VEC) COMPOSE_PROJECT_NAME=$(COMPOSE_PROJECT_NAME) $(COMPOSE_VEC) $(COMPOSE_FILES)

yolo:
	@./scripts/write-compose-ports.sh $(PORT) $(SHADOW_PORT)
	$(COMPOSE_ENV) ./docker/run.sh

box:
	@./scripts/write-compose-ports.sh $(PORT) $(SHADOW_PORT)
	cd docker && $(COMPOSE_ENV) docker compose build box && $(COMPOSE_ENV) docker compose run --rm --service-ports box

install-sqlite-vec:
	@./scripts/install-sqlite-vec.sh

backfill-embeddings:
	@curl -sS -X POST http://127.0.0.1:$(PORT)/rest/backfill/embeddings \
	  -H 'Content-Type: application/json' \
	  -d '{"reason":"make backfill-embeddings"}'
	@echo

start:
	@./scripts/start.sh

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
e2e:
	@HEADED=$(HEADED) NO_BUILD=$(NO_BUILD) T='$(T)' ./scripts/e2e.sh

deploy: test e2e
	npm i
	npx shadow-cljs release app
	clj -T:build jar
	@if [ ! -d "$(DEPLOY_TARGET)" ]; then echo "deploy target not found: $(DEPLOY_TARGET)"; exit 1; fi
	@if [ -f "$(DEPLOY_TARGET)/server.jar" ]; then cp "$(DEPLOY_TARGET)/server.jar" "$(DEPLOY_TARGET)/server.jar.bkp"; fi
	cp server.jar "$(DEPLOY_TARGET)/server.jar"
	rm server.jar
	@echo "deployed server.jar -> $(DEPLOY_TARGET)"

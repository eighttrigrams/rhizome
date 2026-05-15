PORT ?= 3006
E2E_PORT ?= 3005
SHADOW_PORT ?= 8020
SHADOW_NREPL_PORT ?= 9630
DEPLOY_TARGET ?= $(HOME)/Applications/rhizome

.PHONY: start stop test e2e deploy install-sqlite-vec yolo box backfill-embeddings clean

onboard:
	./scripts/onboard.sh

clean:
	rm -f config.edn rhizome.db rhizome-test.db
	rm -f test/rhizome-e2e.db
	rm -f docker/.env
	rm -f *.db-journal *.db-wal *.db-shm
	rm -f test/*.db-journal test/*.db-wal test/*.db-shm

# When WITH_VEC=1, also activate the `vec` compose profile so the Ollama
# sidecar starts. Otherwise it stays absent and devs who don't need semsearch
# never pay the 3 GB pull cost.
COMPOSE_VEC = $(if $(filter 1,$(WITH_VEC)),COMPOSE_PROFILES=vec,)

yolo:
	WITH_VEC=$(WITH_VEC) $(COMPOSE_VEC) ./docker/run.sh

box:
	cd docker && $(COMPOSE_VEC) WITH_VEC=$(WITH_VEC) docker compose build box && $(COMPOSE_VEC) WITH_VEC=$(WITH_VEC) docker compose run --rm --service-ports box

install-sqlite-vec:
	@./scripts/install-sqlite-vec.sh

backfill-embeddings:
	@curl -sS -X POST http://127.0.0.1:$(PORT)/rest/backfill/embeddings \
	  -H 'Content-Type: application/json' \
	  -d '{"reason":"make backfill-embeddings"}'
	@echo

start:
	@if lsof -nP -iTCP:$(E2E_PORT) -sTCP:LISTEN >/dev/null 2>&1; then \
	  echo "e2e server is running on :$(E2E_PORT) (pid $$(lsof -nP -iTCP:$(E2E_PORT) -sTCP:LISTEN -t)). Wait for it to finish, or stop it, before starting dev."; \
	  exit 1; \
	fi
	@for p in $(PORT) $(SHADOW_PORT); do \
	  if lsof -nP -iTCP:$$p -sTCP:LISTEN >/dev/null 2>&1; then \
	    echo "already running on :$$p (pid $$(lsof -nP -iTCP:$$p -sTCP:LISTEN -t))"; \
	    exit 1; \
	  fi; \
	done
	@mkdir -p logs
	@if [ -f /.dockerenv ]; then echo container > .dev-server.lock; else echo host > .dev-server.lock; fi
	@echo "starting dev server on :$(PORT) (logs: logs/dev.out)"
	@nohup clj -M:dev -m server > logs/dev.out 2>&1 &
	@echo "starting shadow-cljs watch on :$(SHADOW_PORT) (logs: logs/shadow.out)"
	@nohup npx shadow-cljs watch app > logs/shadow.out 2>&1 &

# Only kills what this project bound: the JVM on $(PORT) and the node process
# holding $(SHADOW_PORT) (rhizome's :dev-http). That same node process also
# holds shadow's primary port (default 9630), so killing it frees both —
# without us probing 9630 and risking somebody else's shadow project.
#
# .dev-server.lock (written by `make start`) records which side -- host or
# container -- owns the running server. Refuse to tear down a server started
# from the other side: on macOS, `lsof -ti:$PORT` from the host returns
# Docker's port-forward proxy PIDs, and killing those breaks the container's
# networking; the reverse misses the real PID entirely.
stop:
	@listening=0; for p in $(PORT) $(SHADOW_PORT); do \
	  if lsof -nP -iTCP:$$p -sTCP:LISTEN >/dev/null 2>&1; then listening=1; fi; \
	done; \
	if [ $$listening -eq 0 ]; then \
	  echo "nothing to stop"; \
	  rm -f .dev-server.lock; \
	  exit 0; \
	fi; \
	if [ -f /.dockerenv ]; then here=container; else here=host; fi; \
	owner=$$(cat .dev-server.lock 2>/dev/null); \
	if [ -z "$$owner" ]; then \
	  if [ ! -f /.dockerenv ]; then \
	    echo "ports are held but no .dev-server.lock -- most likely Docker's port-forwarder for a running container. Exit the container (or 'docker compose down') and try again."; \
	  else \
	    echo "ports are held but .dev-server.lock is missing -- refusing to kill an unknown process. Investigate manually."; \
	  fi; \
	  exit 1; \
	fi; \
	if [ "$$owner" != "$$here" ]; then \
	  echo "dev server was started from the $$owner; run 'make stop' there (you are on the $$here)"; \
	  exit 1; \
	fi; \
	for p in $(PORT) $(SHADOW_PORT); do \
	  pids=$$(lsof -nP -iTCP:$$p -sTCP:LISTEN -t 2>/dev/null); \
	  if [ -n "$$pids" ]; then \
	    echo "killing $$pids on :$$p"; \
	    kill $$pids; \
	  fi; \
	done; \
	rm -f .dev-server.lock

test:
	@vec_path="$${SQLITE_VEC_PATH:-./.sqlite-vec/vec0}"; \
	case "$$(uname -s)" in Darwin) ext=dylib;; *) ext=so;; esac; \
	if [ -f "$${vec_path}.$${ext}" ]; then \
	  clj -M:test \
	    && echo "tests passed (including :vector tests; sqlite-vec found at $${vec_path}.$${ext})"; \
	else \
	  echo "sqlite-vec not installed; excluding ^:vector tests"; \
	  clj -M:test --exclude :vector \
	    && echo "tests passed (WITHOUT :vector tests; sqlite-vec not installed)"; \
	fi

HEADED ?= 0
# Build the cljs release bundle here, before playwright spawns its webServer.
# Doing the release inside playwright's child can hang on a cold .shadow-cljs
# cache (no output past the config banner), and either way the cache is reused
# afterwards so this step is fast on subsequent runs.
e2e:
	npx shadow-cljs release app
	HEADED=$(HEADED) E2E_PORT=$(E2E_PORT) npm run e2e

deploy: test e2e
	npm i
	npx shadow-cljs release app
	clj -T:build jar
	@if [ ! -d "$(DEPLOY_TARGET)" ]; then echo "deploy target not found: $(DEPLOY_TARGET)"; exit 1; fi
	@if [ -f "$(DEPLOY_TARGET)/server.jar" ]; then cp "$(DEPLOY_TARGET)/server.jar" "$(DEPLOY_TARGET)/server.jar.bkp"; fi
	cp server.jar "$(DEPLOY_TARGET)/server.jar"
	rm server.jar
	@echo "deployed server.jar -> $(DEPLOY_TARGET)"

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
	@echo "starting dev server on :$(PORT) (logs: logs/dev.out)"
	@nohup clj -M:dev -m server > logs/dev.out 2>&1 &
	@echo "starting shadow-cljs watch on :$(SHADOW_PORT) (logs: logs/shadow.out)"
	@nohup npx shadow-cljs watch app > logs/shadow.out 2>&1 &

# Only kills what this project bound: the JVM on $(PORT) and the node process
# holding $(SHADOW_PORT) (rhizome's :dev-http). That same node process also
# holds shadow's primary port (default 9630), so killing it frees both —
# without us probing 9630 and risking somebody else's shadow project.
stop:
	@any=0; for p in $(PORT) $(SHADOW_PORT); do \
	  pids=$$(lsof -nP -iTCP:$$p -sTCP:LISTEN -t 2>/dev/null); \
	  if [ -n "$$pids" ]; then \
	    echo "killing $$pids on :$$p"; \
	    kill $$pids; \
	    any=1; \
	  fi; \
	done; \
	if [ $$any -eq 0 ]; then echo "nothing to stop"; fi

test:
	@vec_path="$${SQLITE_VEC_PATH:-./.sqlite-vec/vec0}"; \
	case "$$(uname -s)" in Darwin) ext=dylib;; *) ext=so;; esac; \
	if [ -f "$${vec_path}.$${ext}" ]; then \
	  clj -M:test; \
	else \
	  echo "sqlite-vec not installed; excluding ^:vector tests"; \
	  clj -M:test --exclude :vector; \
	fi

HEADED ?= 0
e2e:
	HEADED=$(HEADED) npm run e2e

deploy: test e2e
	npm i
	npx shadow-cljs release app
	clj -T:build jar
	@if [ ! -d "$(DEPLOY_TARGET)" ]; then echo "deploy target not found: $(DEPLOY_TARGET)"; exit 1; fi
	@if [ -f "$(DEPLOY_TARGET)/server.jar" ]; then cp "$(DEPLOY_TARGET)/server.jar" "$(DEPLOY_TARGET)/server.jar.bkp"; fi
	cp server.jar "$(DEPLOY_TARGET)/server.jar"
	rm server.jar
	@echo "deployed server.jar -> $(DEPLOY_TARGET)"

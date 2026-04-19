PORT ?= 3006

.PHONY: start stop restart test

start:
	@if lsof -nP -iTCP:$(PORT) -sTCP:LISTEN >/dev/null 2>&1; then \
	  echo "already running on :$(PORT) (pid $$(lsof -nP -iTCP:$(PORT) -sTCP:LISTEN -t))"; \
	  exit 1; \
	fi
	@echo "starting dev server on :$(PORT) (logs: dev.out)"
	@nohup ./dev.sh > dev.out 2>&1 &

stop:
	@pids=$$(lsof -nP -iTCP:$(PORT) -sTCP:LISTEN -t 2>/dev/null); \
	if [ -n "$$pids" ]; then \
	  echo "killing $$pids on :$(PORT)"; \
	  kill $$pids; \
	else \
	  echo "nothing listening on :$(PORT)"; \
	fi

restart: stop
	@sleep 1
	@$(MAKE) start

test:
	clj -M:test

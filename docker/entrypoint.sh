#!/bin/bash
# Bind-mounted repos appear with the host UID inside the container, so any git
# invocation in any mounted repo trips "dubious ownership".
git config --global --add safe.directory '*'

# docker-compose bind-mounts ./.mcp.json over /workspace/rhizome/.mcp.json, so
# the container always sees a different file than what's committed at HEAD.
# Without skip-worktree, every `git status` inside the container would list
# .mcp.json as modified -- noisy, and easy to accidentally `git add .` it.
# The bit is stored in .git/index (shared with the host via the workspace
# mount), so this is idempotent and persists for the life of the clone.
if [ -d /workspace/rhizome/.git ]; then
  git -C /workspace/rhizome update-index --skip-worktree \
      .mcp.json \
      .claude/settings.json 2>/dev/null || true
fi

# Auto-install node deps on first container run. The node_modules volume keeps
# this cached afterwards so re-entering the container is fast. Sentinel marker
# avoids running npm install on every shell start; delete .npm-installed to
# force a re-run.
if [ -f /workspace/rhizome/package.json ] && [ ! -f /workspace/rhizome/node_modules/.npm-installed ]; then
  echo "[entrypoint] running npm install (first run)..."
  (cd /workspace/rhizome && npm install && touch node_modules/.npm-installed) \
    || echo "[entrypoint] WARNING: npm install failed; run it manually" >&2
fi

# When the image was built with WITH_VEC=1, the Ollama sidecar service runs
# alongside this container. Forward 127.0.0.1:11434 -> ollama:11434 with socat
# so the same config.edn (:ollama-url "http://127.0.0.1:11434") works on host
# and in the container -- no docker-specific override needed.
if [ "$(cat /etc/rhizome-use-ollama 2>/dev/null)" = "1" ]; then
  socat TCP-LISTEN:11434,fork,reuseaddr TCP:ollama:11434 >/tmp/socat-ollama.log 2>&1 &

  if timeout 60 bash -c 'until curl -fsS http://127.0.0.1:11434/ >/dev/null 2>&1; do sleep 1; done'; then
    if ! curl -fsS http://127.0.0.1:11434/api/tags 2>/dev/null | grep -q '"name":"nomic-embed-text'; then
      echo "[entrypoint] pulling nomic-embed-text into the ollama sidecar (first run)..."
      curl -fsS -X POST http://127.0.0.1:11434/api/pull \
        -H 'Content-Type: application/json' \
        -d '{"name":"nomic-embed-text","stream":false}' >/dev/null \
        || echo "[entrypoint] WARNING: model pull failed; retry by restarting the ollama service" >&2
    fi
  else
    echo "[entrypoint] WARNING: ollama sidecar did not respond; semsearch will be unavailable" >&2
  fi
fi

exec "${@:-/bin/bash}"

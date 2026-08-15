#!/bin/bash
# Bind-mounted repos appear with the host UID inside the container, so any git
# invocation in any mounted repo trips "dubious ownership".
git config --global --add safe.directory '*'

# Auto-install node deps on first container run. The node_modules volume keeps
# this cached afterwards so re-entering the container is fast. Sentinel marker
# avoids running npm install on every shell start; delete .npm-installed to
# force a re-run.
#
# Fresh named volumes mounted on workspace subpaths land as root-owned. The box
# runs as root and doesn't care; a non-root consumer of this image needs to
# claim them first, hence the sudo branch (which no-ops when the dir is already
# writable, and when sudo isn't installed).
for d in /workspace/rhizome/node_modules /workspace/rhizome/.shadow-cljs /workspace/rhizome/.cpcache; do
  if [ -d "$d" ] && [ ! -w "$d" ] && command -v sudo >/dev/null 2>&1; then
    sudo chown -R "$(id -u):$(id -g)" "$d"
  fi
done

if [ -f /workspace/rhizome/package.json ] && [ ! -f /workspace/rhizome/node_modules/.npm-installed ]; then
  echo "[entrypoint] running npm install (first run)..."
  (cd /workspace/rhizome && npm install && touch node_modules/.npm-installed) \
    || echo "[entrypoint] WARNING: npm install failed; run it manually" >&2
fi

# When the image was built with WITH_VEC=1, the Ollama sidecar service runs
# alongside this container. Forward 127.0.0.1:11437 -> ollama:11434 with socat
# so the bridge port doesn't collide with a host-side ollama on :11434 when
# the host is exposed through the container's network namespace. The JVM
# reads :ollama-url via aero #or [#env VEC_URL ...] and the Dockerfile sets
# VEC_URL=http://127.0.0.1:11437, so the same config.edn still works on host
# (falls back to :11434) and in the container (uses :11437 -> sidecar).
if [ "$(cat /etc/rhizome-use-ollama 2>/dev/null)" = "1" ]; then
  socat TCP-LISTEN:11437,fork,reuseaddr TCP:ollama:11434 >/tmp/socat-ollama.log 2>&1 &

  if timeout 60 bash -c 'until curl -fsS http://127.0.0.1:11437/ >/dev/null 2>&1; do sleep 1; done'; then
    if ! curl -fsS http://127.0.0.1:11437/api/tags 2>/dev/null | grep -q '"name":"qwen3-embedding:0.6b'; then
      echo "[entrypoint] pulling qwen3-embedding:0.6b into the ollama sidecar (first run, ~639 MB)..."
      # Stream the pull and project each JSON-line event into a single line of
      # human progress. The pull only happens once per ollama_models volume, so
      # the noise is bounded.
      curl -fsSN -X POST http://127.0.0.1:11437/api/pull \
        -H 'Content-Type: application/json' \
        -d '{"name":"qwen3-embedding:0.6b"}' \
        | jq -r --unbuffered '
            if .total and .completed then
              "[ollama] " + .status + ": "
              + ((.completed / 1048576) | floor | tostring) + "/"
              + ((.total     / 1048576) | floor | tostring) + " MB"
            elif .status then "[ollama] " + .status
            else "" end' \
        || echo "[entrypoint] WARNING: model pull failed; retry by restarting the ollama service" >&2
    fi
  else
    echo "[entrypoint] WARNING: ollama sidecar did not respond; semsearch will be unavailable" >&2
  fi
fi

# Remove the dev-server ownership lock on container exit so the host side
# isn't left thinking a (now-gone) container still owns the dev server. Note:
# no `exec` below -- we need this shell to stick around so the trap fires.
trap 'rm -f /workspace/rhizome/.dev-server.lock' EXIT

"${@:-/bin/bash}"

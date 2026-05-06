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
  git -C /workspace/rhizome update-index --skip-worktree .mcp.json 2>/dev/null || true
fi

exec /bin/bash "$@"

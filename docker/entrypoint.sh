#!/bin/bash
# docker-compose bind-mounts ./.mcp.json over /workspace/.mcp.json, so the
# container always sees a different file than what's committed at HEAD.
# Without skip-worktree, every `git status` inside the container would list
# .mcp.json as modified -- noisy, and easy to accidentally `git add .` it.
# The bit is stored in .git/index (shared with the host via the workspace
# mount), so this is idempotent and persists for the life of the clone.
if [ -d /workspace/.git ]; then
  git -C /workspace update-index --skip-worktree .mcp.json 2>/dev/null || true
fi

exec /bin/bash "$@"

#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOKEN_FILE="$SCRIPT_DIR/token"
GH_TOKEN_FILE="$SCRIPT_DIR/gh_token"

if [ ! -f "$TOKEN_FILE" ]; then
  echo "Token file not found at: $TOKEN_FILE"
  echo "Run 'claude setup-token' on the host and save the sk-ant-oat01-... line to that file."
  exit 1
fi

export CLAUDE_CODE_OAUTH_TOKEN=$(cat "$TOKEN_FILE")
[ -f "$GH_TOKEN_FILE" ] && export GH_TOKEN=$(cat "$GH_TOKEN_FILE")

cd "$SCRIPT_DIR"
docker compose run --rm --service-ports claude

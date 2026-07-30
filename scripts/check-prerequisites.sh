#!/usr/bin/env bash
set -euo pipefail

missing=0

check_tool() {
  local name="$1"
  shift
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "WARN: ${name} is not installed or is not available on PATH." >&2
    missing=1
    return
  fi
  echo "[${name}]"
  "$@" 2>&1 | head -n 3
}

check_tool "Java" java -version
check_tool "Node.js" node --version
check_tool "npm" npm --version
check_tool "Docker" docker --version

if command -v docker >/dev/null 2>&1; then
  check_tool "Docker Compose" docker compose version
fi

exit "${missing}"


#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
DEPLOY_ROOT="${DEPLOY_ROOT:-$PWD/production}"
for slot in blue green; do
  pidfile="$DEPLOY_ROOT/$slot/server.pid"
  if [[ -f "$pidfile" ]]; then
    pid="$(cat "$pidfile")"
    if [[ "$pid" =~ ^[0-9]+$ ]]; then kill "$pid" 2>/dev/null || true; fi
    rm -f "$pidfile"
  fi
done
echo 'Stopped QueueFlow server instances.'

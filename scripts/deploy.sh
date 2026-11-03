#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f build/queueflow.jar ]] || { echo 'Run bash scripts/build.sh first.' >&2; exit 1; }
DEPLOY_ROOT="${DEPLOY_ROOT:-$PWD/production}"
mkdir -p "$DEPLOY_ROOT"
DEPLOY_ROOT="$(cd "$DEPLOY_ROOT" && pwd)"
LOG="$DEPLOY_ROOT/monitoring.log"
BLUE_PORT="${BLUE_PORT:-18180}"
GREEN_PORT="${GREEN_PORT:-18181}"
CURRENT="$DEPLOY_ROOT/current"

if [[ -L "$CURRENT" && "$(readlink "$CURRENT")" == "$DEPLOY_ROOT/blue" ]]; then
  CANDIDATE=green; PORT="$GREEN_PORT"; OLD=blue
else
  CANDIDATE=blue; PORT="$BLUE_PORT"; OLD=green
fi
SLOT="$DEPLOY_ROOT/$CANDIDATE"
mkdir -p "$SLOT"

stop_slot() {
  local slot="$1" pidfile="$DEPLOY_ROOT/$1/server.pid"
  if [[ -f "$pidfile" ]]; then
    local pid
    pid="$(cat "$pidfile")"
    if [[ "$pid" =~ ^[0-9]+$ ]]; then kill "$pid" 2>/dev/null || true; fi
    rm -f "$pidfile"
  fi
}

# The inactive slot is safe to replace. The current slot stays untouched until healthy.
stop_slot "$CANDIDATE"
cp build/queueflow.jar "$SLOT/queueflow.jar"
START_FLAGS=()
if [[ "${INJECT_FAILURE:-0}" == "1" ]]; then START_FLAGS+=(--fail-health); fi
java -jar "$SLOT/queueflow.jar" --port "$PORT" "${START_FLAGS[@]}" > "$SLOT/server.log" 2>&1 &
CANDIDATE_PID=$!
echo "$CANDIDATE_PID" > "$SLOT/server.pid"
echo "Starting candidate: $CANDIDATE on port $PORT"

READY=0
for ((n=1; n <= ${CHECK_ATTEMPTS:-12}; n++)); do
  if bash scripts/health-check.sh "http://127.0.0.1:$PORT" "$LOG"; then READY=1; break; fi
  sleep 1
done

if [[ "$READY" != 1 ]]; then
  stop_slot "$CANDIDATE"
  echo "$(date -u +'%Y-%m-%dT%H:%M:%SZ') ROLLBACK reason=unhealthy-candidate slot=$CANDIDATE active=$OLD" | tee -a "$LOG"
  echo "Deploy rejected: previous active version was preserved."
  exit 1
fi

# Atomic switch takes place only after the candidate passes its health check.
ln -sfn "$SLOT" "$DEPLOY_ROOT/.current-next"
mv -Tf "$DEPLOY_ROOT/.current-next" "$CURRENT"
printf 'ACTIVE_SLOT=%q\nACTIVE_PORT=%q\n' "$CANDIDATE" "$PORT" > "$DEPLOY_ROOT/.active-next"
mv -f "$DEPLOY_ROOT/.active-next" "$DEPLOY_ROOT/active.env"
echo "$(date -u +'%Y-%m-%dT%H:%M:%SZ') DEPLOY status=SUCCESS slot=$CANDIDATE port=$PORT" | tee -a "$LOG"
if [[ "$OLD" != "$CANDIDATE" ]]; then stop_slot "$OLD"; fi
echo "Deploy OK: current -> $CANDIDATE at http://127.0.0.1:$PORT"

#!/usr/bin/env bash
set -euo pipefail
# Usage: bash scripts/health-check.sh http://127.0.0.1:18180 [log-file]
BASE_URL="${1:?Usage: health-check.sh BASE_URL [LOG_FILE]}"
LOG_FILE="${2:-production/monitoring.log}"
mkdir -p "$(dirname "$LOG_FILE")"
STAMP="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT
HTTP_CODE="$(curl --silent --show-error --max-time 3 --output "$BODY_FILE" --write-out '%{http_code}' "${BASE_URL%/}/api/health" 2>/dev/null)" || HTTP_CODE="000"
if [[ "$HTTP_CODE" == "200" ]] && grep -q '"status":"ok"' "$BODY_FILE"; then
  echo "$STAMP status=UP code=200 url=$BASE_URL" | tee -a "$LOG_FILE"
  exit 0
fi
echo "$STAMP status=DOWN code=$HTTP_CODE url=$BASE_URL" | tee -a "$LOG_FILE"
exit 1

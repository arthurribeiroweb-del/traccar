#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 4 ]; then
  echo "Usage: $0 <base_url> <email_or_login> <password> <backup_zip> [replace|merge] [totp]"
  exit 1
fi

BASE_URL="$1"
EMAIL="$2"
PASSWORD="$3"
BACKUP_ZIP="$4"
MODE="${5:-replace}"
TOTP="${6:-}"

if [ ! -f "$BACKUP_ZIP" ]; then
  echo "Backup file not found: $BACKUP_ZIP"
  exit 1
fi

COOKIE="$(mktemp)"
trap 'rm -f "$COOKIE"' EXIT

curl -s -c "$COOKIE" -X POST \
  -d "email=$EMAIL&password=$PASSWORD&code=$TOTP" \
  "$BASE_URL/api/session" > /dev/null

JOB_JSON="$(curl -s -b "$COOKIE" -X POST \
  -H "X-Backup-Confirm: RESTORE" \
  -H "X-Backup-Password: $PASSWORD" \
  -H "X-Backup-Mode: $MODE" \
  -H "X-Backup-Totp: $TOTP" \
  --data-binary "@$BACKUP_ZIP" \
  "$BASE_URL/api/admin/backup/import")"

JOB_ID="$(python - <<'PY' "$JOB_JSON"
import json,sys
print(json.loads(sys.argv[1]).get("id",""))
PY
)"

if [ -z "$JOB_ID" ]; then
  echo "Failed to start restore"
  exit 1
fi

while true; do
  STATUS_JSON="$(curl -s -b "$COOKIE" "$BASE_URL/api/admin/backup/status?id=$JOB_ID")"
  STATE="$(python - <<'PY' "$STATUS_JSON"
import json,sys
print(json.loads(sys.argv[1]).get("state",""))
PY
)"
  if [ "$STATE" = "SUCCESS" ]; then
    break
  fi
  if [ "$STATE" = "ERROR" ]; then
    echo "Restore failed"
    echo "$STATUS_JSON"
    exit 1
  fi
  sleep 2
done

echo "Restore completed"

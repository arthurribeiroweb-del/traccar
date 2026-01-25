#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 3 ]; then
  echo "Usage: $0 <base_url> <email_or_login> <password> [output_dir] [totp]"
  exit 1
fi

BASE_URL="$1"
EMAIL="$2"
PASSWORD="$3"
OUTPUT_DIR="${4:-.}"
TOTP="${5:-}"

COOKIE="$(mktemp)"
trap 'rm -f "$COOKIE"' EXIT

curl -s -c "$COOKIE" -X POST \
  -d "email=$EMAIL&password=$PASSWORD&code=$TOTP" \
  "$BASE_URL/api/session" > /dev/null

JOB_JSON="$(curl -s -b "$COOKIE" -X POST "$BASE_URL/api/admin/backup/export")"
JOB_ID="$(python - <<'PY' "$JOB_JSON"
import json,sys
print(json.loads(sys.argv[1]).get("id",""))
PY
)"

if [ -z "$JOB_ID" ]; then
  echo "Failed to start backup"
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
    echo "Backup failed"
    echo "$STATUS_JSON"
    exit 1
  fi
  sleep 2
done

FILE_NAME="$(python - <<'PY' "$STATUS_JSON"
import json,sys
print(json.loads(sys.argv[1]).get("fileName","backup.zip"))
PY
)"

mkdir -p "$OUTPUT_DIR"
curl -s -b "$COOKIE" -o "$OUTPUT_DIR/$FILE_NAME" "$BASE_URL/api/admin/backup/export/$JOB_ID"
echo "Backup saved to $OUTPUT_DIR/$FILE_NAME"

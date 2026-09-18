#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 3 ]]; then
  echo "usage: $0 <host> <database> <user> [port]" >&2
  exit 64
fi
db_host=$1; db_name=$2; db_user=$3; db_port=${4:-5432}
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
sql="$script_dir/../jsd-aird-api/src/main/resources/db/r01/preflight.sql"
result=$(psql -X -A -t -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -f "$sql")
printf '%s\n' "$result"
if printf '%s' "$result" | grep -q '"classification": "INCOMPATIBLE_FOUNDATION"'; then exit 2; fi

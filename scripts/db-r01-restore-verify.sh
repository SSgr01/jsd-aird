#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 7 || $7 != --apply ]]; then echo "usage: $0 <dump> <manifest> <host> <database> <user> <expected-empty-database> --apply [port]" >&2; exit 64; fi
: "${PGPASSWORD:?set PGPASSWORD; it is never persisted}"
dump=$1; manifest=$2; db_host=$3; db_name=$4; db_user=$5; expected=$6; db_port=${8:-5432}
[[ $db_name == "$expected" ]] || { echo 'target identity mismatch' >&2; exit 65; }
tables=$(psql -X -A -t -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -c "select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema')")
[[ $tables == 0 ]] || { echo 'restore target must be empty' >&2; exit 65; }
pg_restore --exit-on-error --no-owner --no-privileges -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" "$dump"
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
actual=$(psql -X -A -t -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -f "$script_dir/../jsd-aird-api/src/main/resources/db/r01/protected_data_manifest.sql")
expected_digest=$(sed -n 's/.*"protectedDataDigest": "\([0-9a-f]*\)".*/\1/p' "$manifest")
actual_digest=$(printf '%s' "$actual" | sed -n 's/.*"protectedDataDigest": "\([0-9a-f]*\)".*/\1/p')
[[ -n $expected_digest && $actual_digest == "$expected_digest" ]] || {
  echo "restore verification failed: protected-data digest differs" >&2
  exit 2
}
printf '{"restoredDatabase":"%s","protectedDataDigest":"%s","manifestMatch":true}\n' "$db_name" "$actual_digest"

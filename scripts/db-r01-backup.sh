#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 4 ]]; then echo "usage: $0 <host> <database> <user> <outside-repo-directory> [port]" >&2; exit 64; fi
: "${PGPASSWORD:?set PGPASSWORD; it is never persisted}"
db_host=$1; db_name=$2; db_user=$3; backup_dir=$4; db_port=${5:-5432}
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); repo=$(cd "$script_dir/.." && pwd)
mkdir -p "$backup_dir"; backup_dir=$(cd "$backup_dir" && pwd)
case "$backup_dir/" in
  "$repo/"*) echo 'backup directory must be outside the repository' >&2; exit 65 ;;
esac
stamp=$(date +%Y%m%d-%H%M%S); dump="$backup_dir/$db_name-$stamp.dump"; manifest="$backup_dir/$db_name-$stamp.manifest.json"
pg_dump -Fc --no-owner --no-privileges -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -f "$dump"
psql -X -A -t -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -f "$repo/jsd-aird-api/src/main/resources/db/r01/protected_data_manifest.sql" > "$manifest"
printf '{"dump":"%s","manifest":"%s","dumpSha256":"%s"}\n' "$dump" "$manifest" "$(sha256sum "$dump" | cut -d' ' -f1)"

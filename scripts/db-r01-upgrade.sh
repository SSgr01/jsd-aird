#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 4 || ${5:-} != --apply ]]; then
  echo "usage: $0 <host> <database> <user> <expected-database> --apply [port]" >&2
  exit 64
fi
db_host=$1; db_name=$2; db_user=$3; expected=$4; db_port=${6:-5432}
[[ $db_name == "$expected" ]] || { echo 'database identity mismatch' >&2; exit 65; }
: "${PGPASSWORD:?set PGPASSWORD; it is never persisted}"
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
api="$script_dir/../jsd-aird-api"
foundation="$api/src/main/resources/db/foundation/mdm_rnd_foundation.sql"
preflight="$api/src/main/resources/db/r01/preflight.sql"
preserve="$api/src/main/resources/db/r01/preserve_legacy_ai_evidence.sql"
manifest="$api/src/main/resources/db/r01/protected_data_manifest.sql"
export FLYWAY_URL="jdbc:postgresql://$db_host:$db_port/$db_name" FLYWAY_USER="$db_user" FLYWAY_PASSWORD="$PGPASSWORD"
export FLYWAY_LOCATIONS="filesystem:$api/src/main/resources/db/migration"
read -r version actual < <(psql -X -A -t -F ' ' -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -c "select current_setting('server_version_num')::int,current_database()")
(( version >= 160000 && version < 190000 )) || { echo "PostgreSQL 16-18 required; got $version" >&2; exit 65; }
[[ $actual == "$expected" ]] || { echo 'database identity check failed' >&2; exit 65; }
initial_state=$(psql -X -A -t -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -f "$preflight")
if printf '%s' "$initial_state" | grep -q '"classification": "INCOMPATIBLE_FOUNDATION"' &&
   ! printf '%s' "$initial_state" | grep -q '"presentTableCount": 0'; then
  printf '%s\n' "$initial_state"
  exit 2
fi
has_history=$(psql -X -A -t -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -c "select to_regclass('public.flyway_schema_history') is not null")
[[ $has_history == t ]] || (cd "$api" && ./mvnw -q -Dflyway.target=52 flyway:migrate)
(cd "$api" && ./mvnw -q '-Dflyway.ignoreMigrationPatterns=*:pending' flyway:validate)
vector=$(psql -X -A -t -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -c "select exists(select 1 from pg_extension where extname='vector')")
[[ $vector == t ]] || { echo 'pgvector extension is required' >&2; exit 65; }
state=$(psql -X -A -t -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -f "$preflight")
printf '%s' "$state" | grep -q '"classification": "INCOMPATIBLE_FOUNDATION"' && { printf '%s\n' "$state"; exit 2; }
printf '%s' "$state" | grep -q '"classification": "EMPTY_FOUNDATION"' && psql -X -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -f "$foundation"
(cd "$api" && ./mvnw -q flyway:migrate)
(cd "$api" && ./mvnw -q flyway:validate)
psql -X -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -f "$preserve"
final_state=$(psql -X -A -t -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -f "$preflight")
printf '%s\n' "$final_state"
printf '%s' "$final_state" | grep -q '"classification": "COMPLETE_FOUNDATION"' || {
  echo 'final foundation verification failed' >&2
  exit 2
}
psql -X -A -t -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -f "$manifest"

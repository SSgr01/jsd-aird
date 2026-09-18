#!/usr/bin/env bash
set -euo pipefail
if [[ "${R10_APPLY:-}" != "1" ]]; then echo 'Set R10_APPLY=1 only after backup and evidence review.' >&2; exit 2; fi
: "${JSD_AIRD_PSQL_PATH:=psql}"
: "${FLYWAY:=./mvnw}"
: "${PGHOST:?PGHOST is required}"; : "${PGDATABASE:?PGDATABASE is required}"; : "${PGUSER:?PGUSER is required}"
root="$(cd "$(dirname "$0")/.." && pwd)"; api="$root/jsd-aird-api"
preflight_json=$("$JSD_AIRD_PSQL_PATH" -X -A -t -v ON_ERROR_STOP=1 \
  -h "$PGHOST" -p "${PGPORT:-5432}" -U "$PGUSER" -d "$PGDATABASE" \
  -f "$api/src/main/resources/db/r10/preflight.sql")
printf '%s\n' "$preflight_json"
# A first pass only establishes that this is an exact V62 foundation. The
# evidence copy is deliberately run before checking readyForV63 so a database
# that still has legacy links can be made safe in the same explicit invocation.
if [[ "$preflight_json" != *'"currentFlywayVersion":62'* || "$preflight_json" != *'"foundationStatus":"COMPLETE_FOUNDATION"'* ]]; then
  echo 'R10 preflight blocked: expected a complete V62 database.' >&2
  exit 2
fi
"$JSD_AIRD_PSQL_PATH" -X -A -t -v ON_ERROR_STOP=1 -h "$PGHOST" -p "${PGPORT:-5432}" -U "$PGUSER" -d "$PGDATABASE" -f "$api/src/main/resources/db/r01/protected_data_manifest.sql"
"$JSD_AIRD_PSQL_PATH" -X -A -t -v ON_ERROR_STOP=1 -h "$PGHOST" -p "${PGPORT:-5432}" -U "$PGUSER" -d "$PGDATABASE" -f "$api/src/main/resources/db/r01/legacy_ai_deletion_inventory.sql"
"$JSD_AIRD_PSQL_PATH" -X -A -t -v ON_ERROR_STOP=1 -h "$PGHOST" -p "${PGPORT:-5432}" -U "$PGUSER" -d "$PGDATABASE" -f "$api/src/main/resources/db/r01/preserve_legacy_ai_evidence.sql"
post_preserve_json=$("$JSD_AIRD_PSQL_PATH" -X -A -t -v ON_ERROR_STOP=1 \
  -h "$PGHOST" -p "${PGPORT:-5432}" -U "$PGUSER" -d "$PGDATABASE" \
  -f "$api/src/main/resources/db/r10/preflight.sql")
printf '%s\n' "$post_preserve_json"
if [[ "$post_preserve_json" != *'"readyForV63":true'* ]]; then
  echo 'R10 preflight blocked: legacy evidence preservation is incomplete.' >&2
  exit 2
fi
"$JSD_AIRD_PSQL_PATH" -X -A -t -v ON_ERROR_STOP=1 -h "$PGHOST" -p "${PGPORT:-5432}" -U "$PGUSER" -d "$PGDATABASE" -f "$api/src/main/resources/db/r01/legacy_ai_deletion_inventory.sql"
pushd "$api" >/dev/null
FLYWAY_URL="jdbc:postgresql://${PGHOST}:${PGPORT:-5432}/${PGDATABASE}" FLYWAY_USER="$PGUSER" FLYWAY_PASSWORD="${PGPASSWORD:-}" FLYWAY_LOCATIONS="filesystem:$api/src/main/resources/db/migration" "$FLYWAY" -q flyway:validate
FLYWAY_URL="jdbc:postgresql://${PGHOST}:${PGPORT:-5432}/${PGDATABASE}" FLYWAY_USER="$PGUSER" FLYWAY_PASSWORD="${PGPASSWORD:-}" FLYWAY_LOCATIONS="filesystem:$api/src/main/resources/db/migration" "$FLYWAY" -q flyway:migrate
popd >/dev/null

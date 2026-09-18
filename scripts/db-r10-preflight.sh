#!/usr/bin/env bash
set -euo pipefail
: "${JSD_AIRD_PSQL_PATH:=psql}"
: "${PGHOST:?PGHOST is required}"
: "${PGPORT:=5432}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGUSER:?PGUSER is required}"
"$JSD_AIRD_PSQL_PATH" -X -A -t -v ON_ERROR_STOP=1 \
  -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
  -f "$(dirname "$0")/../jsd-aird-api/src/main/resources/db/r10/preflight.sql"

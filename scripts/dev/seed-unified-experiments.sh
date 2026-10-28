#!/usr/bin/env bash
set -euo pipefail
# Non-production fixtures only. Supply SEED_DB_URL, SEED_DB_USER and SEED_DB_PASSWORD.
root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${SEED_DB_URL:?Set SEED_DB_URL to the non-production JDBC URL}"
: "${SEED_DB_USER:?Set SEED_DB_USER}"
: "${SEED_DB_PASSWORD:?Set SEED_DB_PASSWORD}"
cd "$root/jsd-aird-api"
./mvnw -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/seed-classpath.txt
classpath="$root/jsd-aird-api/target/classes:$(cat target/seed-classpath.txt)"
java -cp "$classpath" "$root/scripts/dev/SeedUnifiedExperiments.java" "$SEED_DB_URL" "$SEED_DB_USER" "${1:-80}" "${@:2}"

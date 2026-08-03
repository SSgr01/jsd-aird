#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}/jsd-aird-api"
JSD_AIRD_WORKER_ENABLED=true ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,worker

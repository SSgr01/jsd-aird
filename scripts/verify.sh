#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "${ROOT_DIR}/jsd-aird-web"
npm ci
npm run verify

cd "${ROOT_DIR}/jsd-aird-api"
./mvnw verify


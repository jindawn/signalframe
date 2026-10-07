#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then set -a; source .env; set +a; fi
export NEXT_TELEMETRY_DISABLED=1
exec npm run "${1:-dev}" -w apps/web

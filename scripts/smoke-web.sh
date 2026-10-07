#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export PLAYWRIGHT_BROWSERS_PATH="$PWD/.tools/playwright"
exec npm run smoke -w apps/web

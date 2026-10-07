#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then set -a; source .env; set +a; fi
if [ -x .tools/jdk/Contents/Home/bin/java ]; then export JAVA_HOME="$PWD/.tools/jdk/Contents/Home"; fi
mkdir -p .tools/runtime
task_runtime_jar=$(mktemp "$PWD/.tools/runtime/api-XXXXXXXX")
cp apps/api/target/api-0.1.0.jar "$task_runtime_jar"
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$task_runtime_jar"

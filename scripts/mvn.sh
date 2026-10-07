#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -x .tools/jdk/Contents/Home/bin/java ]; then export JAVA_HOME="$PWD/.tools/jdk/Contents/Home"; fi
task_mvn="$PWD/.tools/maven/bin/mvn"
if [ ! -x "$task_mvn" ]; then task_mvn=$(command -v mvn); fi
exec "$task_mvn" -Dmaven.repo.local="$PWD/.tools/m2" "$@"

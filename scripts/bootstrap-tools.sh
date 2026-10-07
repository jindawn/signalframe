#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tools
if [ "$(uname -s)" != Darwin ]; then
  echo "Use an existing Java 25 and Maven 3.9 on this OS; this bootstrap supports macOS." >&2
  exit 1
fi
task_arch=aarch64
if [ "$(uname -m)" = x86_64 ]; then task_arch=x64; fi
if [ ! -x .tools/maven/bin/mvn ]; then
  curl -fL --retry 2 https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.tar.gz -o .tools/maven.tgz
  tar -xzf .tools/maven.tgz -C .tools
  mv .tools/apache-maven-3.9.11 .tools/maven
fi
if [ ! -x .tools/jdk/Contents/Home/bin/java ]; then
  curl -fL --retry 2 "https://corretto.aws/downloads/latest/amazon-corretto-25-${task_arch}-macos-jdk.tar.gz" -o .tools/jdk.tgz
  mkdir -p .tools/jdk
  tar -xzf .tools/jdk.tgz -C .tools/jdk --strip-components=1
fi

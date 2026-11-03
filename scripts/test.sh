#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f build/queueflow.jar ]]; then bash scripts/build.sh; fi
mkdir -p build/test-classes
mapfile -t sources < <(find src/test/java -name '*.java' | sort)
javac --release 17 -encoding UTF-8 -cp build/queueflow.jar -d build/test-classes "${sources[@]}"
java -cp build/queueflow.jar:build/test-classes queueflow.TestSuite

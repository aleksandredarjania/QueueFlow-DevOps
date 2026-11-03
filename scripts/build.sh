#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf build/classes build/queueflow.jar
mkdir -p build/classes
mapfile -t sources < <(find src/main/java -name '*.java' | sort)
javac --release 17 -encoding UTF-8 -d build/classes "${sources[@]}"
jar --create --file build/queueflow.jar --main-class queueflow.Main \
  -C build/classes . -C src/main/resources public
printf 'BUILD OK: %s (%s bytes)\n' build/queueflow.jar "$(wc -c < build/queueflow.jar)"

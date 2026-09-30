#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/main build/test build/bench
javac -Xlint:all -d build/main $(find src/main/java -name '*.java')
javac -Xlint:all -cp build/main -d build/test $(find src/test/java -name '*.java')
javac -Xlint:all -cp build/main -d build/bench $(find src/bench/java -name '*.java')
echo "build ok"

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
[ -d build/test ] || ./build.sh
java -ea -Xmx2g -cp build/main:build/test cuckoo.CuckooHashMapTest

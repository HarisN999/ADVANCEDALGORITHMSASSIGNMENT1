#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
[ -d build/test ] || ./build.sh
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mutate() {
  local name="$1" from="$2" to="$3"
  rm -rf "$WORK"/*
  mkdir -p "$WORK/src" "$WORK/main" "$WORK/test"
  cp src/main/java/cuckoo/CuckooHashMap.java "$WORK/src/"
  python3 - "$WORK/src/CuckooHashMap.java" "$from" "$to" <<'EOF'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path).read()
if old not in s:
    sys.exit("mutation pattern not found")
open(path, "w").write(s.replace(old, new, 1))
EOF
  javac -nowarn -d "$WORK/main" "$WORK/src/CuckooHashMap.java" 2>/dev/null
  javac -nowarn -cp "$WORK/main" -d "$WORK/test" src/test/java/cuckoo/*.java 2>/dev/null
  if java -ea -Xmx3g -cp "$WORK/main:$WORK/test" cuckoo.CuckooHashMapTest >"$WORK/out.txt" 2>&1; then
    echo "SURVIVED  $name"
  else
    echo "KILLED    $name  ($(grep -m1 '^FAIL' "$WORK/out.txt" | sed 's/^FAIL  //'))"
  fi
}

mutate "evicted key sent to the wrong table" \
"? bucketStart(1, index1(h))
                    : bucketStart(0, index0(h));" \
"? bucketStart(0, index0(h))
                    : bucketStart(1, index1(h));"

mutate "stored hash not moved with the evicted key" \
"            hashes[victim] = h;
            k = vk;" \
"            k = vk;"

mutate "lookup ignores the second table" \
"        start = bucketStart(1, index1(h));
        for (int j = 0; j < bucketSize; j++) {
            int i = start + j;
            Object k = keys[i];
            if (k != null && hashes[i] == h && (k == key || k.equals(key))) return i;
        }
        return -1;" \
"        return -1;"

mutate "rebuild reuses the old hash seeds" \
"        stashLimit = limit;
        pickSeeds();" \
"        stashLimit = limit;"

mutate "remove does not drain the stash" \
"            size--;
            drainStash();" \
"            size--;"

mutate "lookup skips the stash" \
"        int t = findStash(key, h);
        return t >= 0 ? (V) stashVals[t] : null;" \
"        return null;"

mutate "growth check uses > capacity instead of > maxLoad * capacity" \
"if (size + 1 > maxLoad * capacity())" \
"if (size + 1 > capacity())"

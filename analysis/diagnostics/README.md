# Diagnostics for the "lookup cost rises with load" anomaly

These are the ad-hoc probes behind result 2(b) in `REPORT.md`. They aren't part of `bench.sh`. The load sweep they motivated became the `loadsweep` experiment there.

| File | What it measures | Needs |
|---|---|---|
| `Probe2.java` | `containsKey` hit cost for a given bucket size, max load and query order (`rand` or `seq`) | the normal `CuckooHashMap` |
| `Probe3.java` | hit cost split by which table the key lives in (table 0, table 1, random mix) | a copy of `CuckooHashMap` with this extra method added: `public int whichTable(Object key) { int s = findSlot(key, key.hashCode()); return s < 0 ? -1 : (s < tableBuckets * bucketSize ? 0 : 1); }` |
| `Probe4.java` | hit and miss cost vs load at a fixed table size | the normal `CuckooHashMap` |

For the branch-free experiment, `findSlot` in a copy of the class was replaced by a loop that scans the whole bucket with `found = (keys[i] == key) ? i : found;` and has no early exit. It only compares by identity, which is valid in that probe because queries reuse the inserted key objects.

To run the probes:

```bash
./build.sh
mkdir -p build/diag
javac -cp build/main -d build/diag analysis/diagnostics/Probe2.java analysis/diagnostics/Probe4.java
java -Xmx4g -cp build/main:build/diag diagnostics.Probe2 4 2000000 0.9 rand
java -Xmx4g -cp build/main:build/diag diagnostics.Probe4 4 2097152 0.2,0.4,0.6,0.8,0.9
```

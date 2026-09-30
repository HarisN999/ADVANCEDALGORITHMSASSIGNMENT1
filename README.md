# Cuckoo Hashing in Java: Implementation and Empirical Study

Programming Assignment 1, Track A (implementation and empirical study).

This is a cuckoo hash map (`CuckooHashMap<K, V>`) with two hash functions, configurable bucket size, a small stash, and automatic growth. It's benchmarked against `java.util.HashMap`. The full write-up is in [`REPORT.md`](REPORT.md).

## Requirements

- JDK 17 or newer (developed and measured on OpenJDK 21)
- Python 3 with `matplotlib`, only for regenerating plots
- No build tool or external libraries: plain `javac`

## Build, test, benchmark

```bash
./build.sh                  # compiles main, test and bench sources into build/
./test.sh                   # runs the 33-case test suite (~20 s)
./scripts/mutation_check.sh # injects 7 deliberate bugs, checks the tests catch each one (~3 min)
./bench.sh all --quick      # smoke-test every experiment (~1.5 min)
./bench.sh all              # full study (~30 min), writes results/*.csv
./bench.sh lookup           # or run one experiment: lookup|insert|latency|memory|fill|kicks|stash
python3 analysis/plot.py    # regenerates plots/*.png from results/
```

`bench.sh` uses a 4 GB heap. On a machine with less than 8 GB of RAM, lower `-Xmx` in `bench.sh` and drop the 8M size.

## Using the map

```java
CuckooHashMap<String, Integer> m = CuckooHashMap.standard();    // b=1, load <= 0.45, stash 4
CuckooHashMap<String, Integer> m4 = CuckooHashMap.bucketized(); // b=4, load <= 0.90, stash 4

CuckooHashMap<Integer, Integer> custom = CuckooHashMap.builder()
        .bucketSize(2).maxLoad(0.85).stash(2).maxKicks(200).expectedSize(10_000).seed(1)
        .build();

m.put("a", 1); m.get("a"); m.containsKey("a"); m.remove("a"); m.size(); m.forEach((k, v) -> {});
```

Supported: `put`, `get`, `containsKey`, `remove`, `size`, `isEmpty`, `clear`, `forEach`. Null values are allowed. Null keys are rejected on `put`. The map exposes instrumentation for the study (`kicks()`, `rehashes()`, `growths()`, `capacity()`, `loadFactor()`, `stashSize()`, `isDegraded()`) and a `checkInvariants()` self-check used by the tests.

## Repository layout

```
src/main/java/cuckoo/CuckooHashMap.java      the data structure
src/test/java/cuckoo/CuckooHashMapTest.java  self-contained test runner (no JUnit needed)
src/bench/java/cuckoo/bench/                 benchmark harness
    Bench.java    the seven experiments
    Table.java    thin adapter so each JVM only ever loads one map implementation
    Keys.java     key and query generation
bench.sh                 runs each (experiment, implementation) in a fresh JVM
analysis/plot.py         CSV -> PNG
scripts/mutation_check.sh  checks the test suite against injected bugs
results/                 raw CSVs from the run described in the report, plus machine.txt
plots/                   figures used in the report
REPORT.md                the written report
VIDEO_NOTES.md           speaking guide for the walkthrough video
```

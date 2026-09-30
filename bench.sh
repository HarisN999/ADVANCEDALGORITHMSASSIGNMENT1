#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
[ -d build/bench ] || ./build.sh

WHAT="${1:-all}"
QUICK="${2:-}"
OUT=results
mkdir -p "$OUT"
JAVA="java -Xms4g -Xmx4g -XX:+UseParallelGC -XX:-UseAdaptiveSizePolicy -cp build/main:build/bench cuckoo.bench.Bench"
IMPLS="hashmap cuckoo1 cuckoo4"

if [ "$QUICK" = "--quick" ]; then
  SIZES="1024,16384,262144,1048576"
  LAT_N=1000000
  FILL_TRIALS=10
  KICK_TRIALS=5
  STASH_BUDGET=20000000
  MEM_SIZES="100000,200000,400000,800000"
  LOADED_SIZES="14000,220000"
  SWEEP_SLOTS=262144
else
  SIZES="1024,4096,16384,65536,262144,1048576,4194304,8388608"
  LAT_N=4000000
  FILL_TRIALS=40
  KICK_TRIALS=30
  STASH_BUDGET=200000000
  LOADED_SIZES="14000,220000,1700000,7000000"
  SWEEP_SLOTS=4194304
  MEM_SIZES=$(python3 -c "print(','.join(str(int(50000*(4000000/50000)**(i/39))) for i in range(40)))")
fi

run() { echo "[$(date +%H:%M:%S)] $*" >&2; }

machine_info() {
  {
    echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    java -version 2>&1 | grep -v JAVA_TOOL
    lscpu 2>/dev/null | grep -E "Model name|^CPU\(s\)|L1d|L2|L3" || sysctl -n machdep.cpu.brand_string 2>/dev/null || true
  } > "$OUT/machine.txt"
}

lookup() {
  echo "impl,dist,n,kind,trial,ns_per_op" > "$OUT/lookup.csv"
  for dist in random sequential; do
    for impl in $IMPLS; do
      run "lookup $impl $dist"
      $JAVA lookup "$impl" "$dist" "$SIZES" >> "$OUT/lookup.csv"
    done
  done
}

lookup_loaded() {
  echo "impl,dist,n,kind,trial,ns_per_op" > "$OUT/lookup_loaded.csv"
  for impl in $IMPLS; do
    run "lookup at non-power-of-two sizes $impl"
    $JAVA lookup "$impl" random "$LOADED_SIZES" >> "$OUT/lookup_loaded.csv"
  done
}

loadsweep() {
  echo "bucket_size,slots,load,kind,trial,ns_per_op" > "$OUT/loadsweep.csv"
  for b in 1 2 4; do
    run "lookup cost vs load at fixed table size, b=$b"
    $JAVA loadsweep "$b" "$SWEEP_SLOTS" 0.05,0.1,0.2,0.3,0.4,0.45,0.49,0.55,0.6,0.7,0.8,0.85,0.9,0.95 >> "$OUT/loadsweep.csv"
  done
}

insert() {
  echo "impl,dist,n,trial,ns_per_op,kicks,rehashes,growths,capacity,load" > "$OUT/insert.csv"
  for dist in random sequential; do
    for impl in $IMPLS; do
      run "insert $impl $dist"
      $JAVA insert "$impl" "$dist" "$SIZES" >> "$OUT/insert.csv"
    done
  done
}

latency() {
  echo "impl,trial,index,micros" > "$OUT/latency_spikes.csv"
  echo "impl,trial,mean_ns,p50_ns,p99_ns,p999_ns,p9999_ns,max_us,top01_share_pct" > "$OUT/latency_pct.csv"
  for impl in $IMPLS; do
    run "latency $impl"
    $JAVA latency "$impl" "$LAT_N" 3 > "$OUT/latency.tmp"
    grep '^spike,' "$OUT/latency.tmp" | cut -d, -f2- >> "$OUT/latency_spikes.csv" || true
    grep '^pct,' "$OUT/latency.tmp" | cut -d, -f2- >> "$OUT/latency_pct.csv"
    rm -f "$OUT/latency.tmp"
  done
}

memory() {
  echo "impl,n,bytes_per_entry,kicks,rehashes,growths,capacity,load" > "$OUT/memory.csv"
  for impl in $IMPLS; do
    run "memory $impl"
    java -Xms4g -Xmx4g -XX:+UseSerialGC -cp build/main:build/bench cuckoo.bench.Bench memory "$impl" "$MEM_SIZES" >> "$OUT/memory.csv"
  done
}

fill() {
  echo "max_kicks,bucket_size,slots,trial,fail_load" > "$OUT/fill.csv"
  run "fill threshold vs bucket size and table size"
  $JAVA fill 1,2,4,8 1024,16384,262144 "$FILL_TRIALS" 500 >> "$OUT/fill.csv"
  run "fill threshold vs max kicks"
  $JAVA fill 1,4 65536 "$FILL_TRIALS" 5,20,100,2000 >> "$OUT/fill.csv"
}

kicks() {
  echo "bucket_size,load,mean_kicks,max_kicks,samples" > "$OUT/kicks.csv"
  run "kicks per insert vs load"
  $JAVA kicks 1,2,4,8 65536 "$KICK_TRIALS" >> "$OUT/kicks.csv"
}

stash() {
  echo "stash,slots,load,trials,failures,failure_rate" > "$OUT/stash.csv"
  run "stash failure probability"
  $JAVA stash 128,256,512,1024,2048,4096,8192 0,1,2,3 0.40 "$STASH_BUDGET" >> "$OUT/stash.csv"
}

machine_info
case "$WHAT" in
  all) lookup; lookup_loaded; loadsweep; insert; latency; memory; fill; kicks; stash ;;
  lookup|lookup_loaded|loadsweep|insert|latency|memory|fill|kicks|stash) "$WHAT" ;;
  *) echo "usage: ./bench.sh [all|lookup|lookup_loaded|loadsweep|insert|latency|memory|fill|kicks|stash] [--quick]" >&2; exit 1 ;;
esac
run "done"

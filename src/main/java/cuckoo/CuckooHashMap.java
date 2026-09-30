package cuckoo;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.BiConsumer;

public final class CuckooHashMap<K, V> {

    private static final int SAME_SIZE_ATTEMPTS = 3;
    private static final int MAX_EXTRA_DOUBLINGS = 2;
    private static final int MAX_TABLE_BUCKETS = 1 << 29;

    private final int bucketSize;
    private final double maxLoad;
    private final int stashCapacity;
    private final int fixedMaxKicks;
    private final SplittableRandom rng;

    private int tableBuckets;
    private int mask;
    private int maxKicks;
    private int seed0;
    private int seed1;

    private Object[] keys;
    private Object[] vals;
    private int[] hashes;

    private Object[] stashKeys;
    private Object[] stashVals;
    private int[] stashHashes;
    private int stashSize;
    private int stashLimit;
    private boolean degraded;

    private int size;

    private Object pendingKey;
    private Object pendingVal;
    private int pendingHash;

    private long kicks;
    private long rehashes;
    private long growths;
    private long failedWalks;

    private CuckooHashMap(Builder b) {
        if (b.bucketSize < 1) throw new IllegalArgumentException("bucketSize must be >= 1");
        if (!(b.maxLoad > 0.0 && b.maxLoad <= 1.0)) throw new IllegalArgumentException("maxLoad must be in (0, 1]");
        if (b.stashCapacity < 0) throw new IllegalArgumentException("stashCapacity must be >= 0");
        if (b.maxKicks < 0) throw new IllegalArgumentException("maxKicks must be >= 0");
        this.bucketSize = b.bucketSize;
        this.maxLoad = b.maxLoad;
        this.stashCapacity = b.stashCapacity;
        this.fixedMaxKicks = b.maxKicks;
        this.rng = new SplittableRandom(b.seed);
        int requested = b.bucketsPerTable > 0
                ? b.bucketsPerTable
                : (int) Math.ceil(b.expectedSize / (2.0 * bucketSize * maxLoad));
        allocate(nextPowerOfTwo(Math.max(1, requested)));
        this.stashKeys = new Object[stashCapacity];
        this.stashVals = new Object[stashCapacity];
        this.stashHashes = new int[stashCapacity];
        this.stashLimit = stashCapacity;
        pickSeeds();
    }

    public static <K, V> Builder builder() {
        return new Builder();
    }

    public static <K, V> CuckooHashMap<K, V> standard() {
        return new Builder().bucketSize(1).maxLoad(0.45).stash(4).build();
    }

    public static <K, V> CuckooHashMap<K, V> bucketized() {
        return new Builder().bucketSize(4).maxLoad(0.9).stash(4).build();
    }

    public static final class Builder {
        private int bucketSize = 1;
        private double maxLoad = 0.45;
        private int stashCapacity = 4;
        private int maxKicks = 0;
        private int bucketsPerTable = 0;
        private int expectedSize = 16;
        private long seed = 0x9E3779B97F4A7C15L;

        public Builder bucketSize(int b) { this.bucketSize = b; return this; }
        public Builder maxLoad(double l) { this.maxLoad = l; return this; }
        public Builder stash(int s) { this.stashCapacity = s; return this; }
        public Builder maxKicks(int k) { this.maxKicks = k; return this; }
        public Builder bucketsPerTable(int n) { this.bucketsPerTable = n; return this; }
        public Builder expectedSize(int n) { this.expectedSize = n; return this; }
        public Builder seed(long s) { this.seed = s; return this; }

        public <K, V> CuckooHashMap<K, V> build() {
            return new CuckooHashMap<>(this);
        }
    }

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }
    public int capacity() { return 2 * tableBuckets * bucketSize; }
    public double loadFactor() { return (double) size / capacity(); }
    public int bucketSize() { return bucketSize; }
    public int stashSize() { return stashSize; }
    public long kicks() { return kicks; }
    public long rehashes() { return rehashes; }
    public long growths() { return growths; }
    public long failedWalks() { return failedWalks; }
    public boolean isDegraded() { return degraded; }

    @SuppressWarnings("unchecked")
    public V get(Object key) {
        if (key == null) return null;
        int h = key.hashCode();
        int s = findSlot(key, h);
        if (s >= 0) return (V) vals[s];
        int t = findStash(key, h);
        return t >= 0 ? (V) stashVals[t] : null;
    }

    public boolean containsKey(Object key) {
        if (key == null) return false;
        int h = key.hashCode();
        return findSlot(key, h) >= 0 || findStash(key, h) >= 0;
    }

    @SuppressWarnings("unchecked")
    public V put(K key, V value) {
        Objects.requireNonNull(key, "null keys are not supported");
        int h = key.hashCode();
        int s = findSlot(key, h);
        if (s >= 0) {
            V old = (V) vals[s];
            vals[s] = value;
            return old;
        }
        int t = findStash(key, h);
        if (t >= 0) {
            V old = (V) stashVals[t];
            stashVals[t] = value;
            return old;
        }
        if (size + 1 > maxLoad * capacity()) {
            rebuild(tableBuckets * 2, null, null, 0, true);
        }
        insertNew(key, value, h);
        size++;
        return null;
    }

    @SuppressWarnings("unchecked")
    public V remove(Object key) {
        if (key == null) return null;
        int h = key.hashCode();
        int s = findSlot(key, h);
        if (s >= 0) {
            V old = (V) vals[s];
            keys[s] = null;
            vals[s] = null;
            hashes[s] = 0;
            size--;
            drainStash();
            return old;
        }
        int t = findStash(key, h);
        if (t >= 0) {
            V old = (V) stashVals[t];
            removeStashAt(t);
            size--;
            return old;
        }
        return null;
    }

    public void clear() {
        java.util.Arrays.fill(keys, null);
        java.util.Arrays.fill(vals, null);
        java.util.Arrays.fill(hashes, 0);
        java.util.Arrays.fill(stashKeys, null);
        java.util.Arrays.fill(stashVals, null);
        stashSize = 0;
        stashLimit = stashCapacity;
        degraded = false;
        size = 0;
    }

    @SuppressWarnings("unchecked")
    public void forEach(BiConsumer<? super K, ? super V> action) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != null) action.accept((K) keys[i], (V) vals[i]);
        }
        for (int i = 0; i < stashSize; i++) {
            action.accept((K) stashKeys[i], (V) stashVals[i]);
        }
    }

    private int index0(int h) {
        return mix(h ^ seed0) & mask;
    }

    private int index1(int h) {
        return mix(h ^ seed1) & mask;
    }

    private int bucketStart(int table, int bucket) {
        return (table * tableBuckets + bucket) * bucketSize;
    }

    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    private int findSlot(Object key, int h) {
        int start = bucketStart(0, index0(h));
        for (int j = 0; j < bucketSize; j++) {
            int i = start + j;
            Object k = keys[i];
            if (k != null && hashes[i] == h && (k == key || k.equals(key))) return i;
        }
        start = bucketStart(1, index1(h));
        for (int j = 0; j < bucketSize; j++) {
            int i = start + j;
            Object k = keys[i];
            if (k != null && hashes[i] == h && (k == key || k.equals(key))) return i;
        }
        return -1;
    }

    private int findStash(Object key, int h) {
        for (int i = 0; i < stashSize; i++) {
            Object k = stashKeys[i];
            if (stashHashes[i] == h && (k == key || k.equals(key))) return i;
        }
        return -1;
    }

    private boolean placeInEmpty(int start, Object k, Object v, int h) {
        for (int j = 0; j < bucketSize; j++) {
            int i = start + j;
            if (keys[i] == null) {
                keys[i] = k;
                vals[i] = v;
                hashes[i] = h;
                return true;
            }
        }
        return false;
    }

    private boolean cuckooWalk(Object k, Object v, int h) {
        int b0 = bucketStart(0, index0(h));
        if (placeInEmpty(b0, k, v, h)) return true;
        int b1 = bucketStart(1, index1(h));
        if (placeInEmpty(b1, k, v, h)) return true;

        int current = rng.nextBoolean() ? b0 : b1;
        for (int step = 0; step < maxKicks; step++) {
            int victim = bucketSize == 1 ? current : current + rng.nextInt(bucketSize);
            Object vk = keys[victim];
            Object vv = vals[victim];
            int vh = hashes[victim];
            keys[victim] = k;
            vals[victim] = v;
            hashes[victim] = h;
            k = vk;
            v = vv;
            h = vh;
            kicks++;

            int alternate = current < tableBuckets * bucketSize
                    ? bucketStart(1, index1(h))
                    : bucketStart(0, index0(h));
            if (placeInEmpty(alternate, k, v, h)) return true;
            current = alternate;
        }
        pendingKey = k;
        pendingVal = v;
        pendingHash = h;
        failedWalks++;
        return false;
    }

    private void insertNew(Object k, Object v, int h) {
        if (cuckooWalk(k, v, h)) return;
        Object hk = pendingKey;
        Object hv = pendingVal;
        int hh = pendingHash;
        pendingKey = null;
        pendingVal = null;
        if (stashSize < stashLimit) {
            pushStash(hk, hv, hh);
            return;
        }
        if (degraded) {
            stashLimit++;
            pushStash(hk, hv, hh);
            return;
        }
        rebuild(tableBuckets, hk, hv, hh, false);
    }

    private void rebuild(int targetBuckets, Object extraKey, Object extraVal, int extraHash, boolean growing) {
        Object[] ek = new Object[size + stashSize + 1];
        Object[] ev = new Object[ek.length];
        int[] eh = new int[ek.length];
        int c = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != null) {
                ek[c] = keys[i];
                ev[c] = vals[i];
                eh[c] = hashes[i];
                c++;
            }
        }
        for (int i = 0; i < stashSize; i++) {
            ek[c] = stashKeys[i];
            ev[c] = stashVals[i];
            eh[c] = stashHashes[i];
            c++;
        }
        if (extraKey != null) {
            ek[c] = extraKey;
            ev[c] = extraVal;
            eh[c] = extraHash;
            c++;
        }

        if (growing) growths++;
        int buckets = targetBuckets;
        int doublings = 0;
        for (int attempt = 0; ; attempt++) {
            if (attempt > 0 && attempt % SAME_SIZE_ATTEMPTS == 0) {
                if (doublings == MAX_EXTRA_DOUBLINGS || buckets >= MAX_TABLE_BUCKETS) break;
                buckets *= 2;
                doublings++;
                growths++;
            }
            if (!growing || attempt > 0) rehashes++;
            resetTables(buckets, stashCapacity);
            if (insertAll(ek, ev, eh, c)) {
                degraded = false;
                return;
            }
        }

        rehashes++;
        resetTables(targetBuckets, Integer.MAX_VALUE);
        insertAll(ek, ev, eh, c);
        stashLimit = Math.max(stashCapacity, stashSize);
        degraded = true;
    }

    private void resetTables(int buckets, int limit) {
        allocate(buckets);
        java.util.Arrays.fill(stashKeys, null);
        java.util.Arrays.fill(stashVals, null);
        java.util.Arrays.fill(stashHashes, 0);
        stashSize = 0;
        stashLimit = limit;
        pickSeeds();
    }

    private boolean insertAll(Object[] ek, Object[] ev, int[] eh, int count) {
        for (int i = 0; i < count; i++) {
            if (cuckooWalk(ek[i], ev[i], eh[i])) continue;
            Object hk = pendingKey;
            Object hv = pendingVal;
            int hh = pendingHash;
            pendingKey = null;
            pendingVal = null;
            if (stashSize < stashLimit) {
                pushStash(hk, hv, hh);
                continue;
            }
            return false;
        }
        return true;
    }

    private void pushStash(Object k, Object v, int h) {
        if (stashSize == stashKeys.length) {
            int n = Math.max(4, stashKeys.length * 2);
            stashKeys = java.util.Arrays.copyOf(stashKeys, n);
            stashVals = java.util.Arrays.copyOf(stashVals, n);
            stashHashes = java.util.Arrays.copyOf(stashHashes, n);
        }
        stashKeys[stashSize] = k;
        stashVals[stashSize] = v;
        stashHashes[stashSize] = h;
        stashSize++;
    }

    private void drainStash() {
        for (int i = stashSize - 1; i >= 0; i--) {
            int h = stashHashes[i];
            if (placeInEmpty(bucketStart(0, index0(h)), stashKeys[i], stashVals[i], h)
                    || placeInEmpty(bucketStart(1, index1(h)), stashKeys[i], stashVals[i], h)) {
                removeStashAt(i);
            }
        }
    }

    private void removeStashAt(int t) {
        int last = stashSize - 1;
        stashKeys[t] = stashKeys[last];
        stashVals[t] = stashVals[last];
        stashHashes[t] = stashHashes[last];
        stashKeys[last] = null;
        stashVals[last] = null;
        stashHashes[last] = 0;
        stashSize--;
    }

    private void allocate(int buckets) {
        tableBuckets = buckets;
        mask = buckets - 1;
        int slots = 2 * buckets * bucketSize;
        keys = new Object[slots];
        vals = new Object[slots];
        hashes = new int[slots];
        maxKicks = fixedMaxKicks > 0 ? fixedMaxKicks : 16 + 6 * log2(slots);
    }

    private void pickSeeds() {
        seed0 = rng.nextInt();
        do {
            seed1 = rng.nextInt();
        } while (seed1 == seed0);
    }

    private static int log2(int x) {
        return 31 - Integer.numberOfLeadingZeros(x);
    }

    private static int nextPowerOfTwo(int x) {
        if (x >= MAX_TABLE_BUCKETS) return MAX_TABLE_BUCKETS;
        return x <= 1 ? 1 : Integer.highestOneBit(x - 1) << 1;
    }

    public void checkInvariants() {
        int count = 0;
        Set<Object> seen = new HashSet<>();
        int half = tableBuckets * bucketSize;
        for (int i = 0; i < keys.length; i++) {
            Object k = keys[i];
            if (k == null) {
                if (vals[i] != null) throw new AssertionError("value without key at slot " + i);
                continue;
            }
            count++;
            if (hashes[i] != k.hashCode()) throw new AssertionError("stale stored hash at slot " + i);
            int table = i < half ? 0 : 1;
            int bucket = (i - table * half) / bucketSize;
            int expected = table == 0 ? index0(hashes[i]) : index1(hashes[i]);
            if (bucket != expected) {
                throw new AssertionError("key " + k + " at table " + table + " bucket " + bucket
                        + " but its hash maps to bucket " + expected);
            }
            if (!seen.add(k)) throw new AssertionError("duplicate key " + k);
        }
        if (!degraded && stashSize > stashCapacity) throw new AssertionError("stash overflow outside degraded mode");
        if (stashSize > stashLimit) throw new AssertionError("stash exceeds its limit");
        for (int i = 0; i < stashSize; i++) {
            Object k = stashKeys[i];
            if (k == null) throw new AssertionError("null key in stash");
            if (stashHashes[i] != k.hashCode()) throw new AssertionError("stale stash hash");
            if (!seen.add(k)) throw new AssertionError("duplicate key in stash " + k);
            count++;
        }
        for (int i = stashSize; i < stashKeys.length; i++) {
            if (stashKeys[i] != null) throw new AssertionError("garbage beyond stash size");
        }
        if (count != size) throw new AssertionError("size " + size + " but found " + count + " entries");
    }
}

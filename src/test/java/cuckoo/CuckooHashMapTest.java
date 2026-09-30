package cuckoo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;

public final class CuckooHashMapTest {

    private interface Test {
        void run() throws Exception;
    }

    private record Case(String name, Test body) {}

    private static final List<Case> CASES = new ArrayList<>();

    private static void test(String name, Test body) {
        CASES.add(new Case(name, body));
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void eq(Object expected, Object actual, String msg) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(msg + ": expected " + expected + " but got " + actual);
        }
    }

    private static final class FixedHash {
        final int id;
        final int hash;

        FixedHash(int id, int hash) {
            this.id = id;
            this.hash = hash;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof FixedHash f && f.id == id;
        }

        @Override
        public String toString() {
            return "FixedHash(" + id + ")";
        }
    }

    private static final class MutableKey {
        int value;

        MutableKey(int value) {
            this.value = value;
        }

        @Override
        public int hashCode() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MutableKey m && m.value == value;
        }
    }

    private record Config(String name, int bucketSize, double maxLoad, int stash, int initialBuckets) {
        <K, V> CuckooHashMap<K, V> build(long seed) {
            return CuckooHashMap.builder()
                    .bucketSize(bucketSize)
                    .maxLoad(maxLoad)
                    .stash(stash)
                    .bucketsPerTable(initialBuckets)
                    .seed(seed)
                    .build();
        }
    }

    private static final Config[] CONFIGS = {
            new Config("b=1 stash=0", 1, 0.45, 0, 1),
            new Config("b=1 stash=4", 1, 0.45, 4, 1),
            new Config("b=1 load=0.49", 1, 0.49, 2, 1),
            new Config("b=2", 2, 0.85, 4, 1),
            new Config("b=4", 4, 0.90, 4, 1),
            new Config("b=4 load=0.97", 4, 0.97, 0, 1),
            new Config("b=8", 8, 0.95, 2, 1),
    };

    static {
        test("empty map", () -> {
            CuckooHashMap<Integer, String> m = CuckooHashMap.standard();
            eq(0, m.size(), "size");
            check(m.isEmpty(), "isEmpty");
            eq(null, m.get(1), "get on empty");
            check(!m.containsKey(1), "containsKey on empty");
            eq(null, m.remove(1), "remove on empty");
            m.checkInvariants();
        });

        test("put returns previous value and replaces", () -> {
            CuckooHashMap<String, Integer> m = CuckooHashMap.standard();
            eq(null, m.put("a", 1), "first put");
            eq(1, m.put("a", 2), "second put");
            eq(2, m.get("a"), "get after replace");
            eq(1, m.size(), "size after replace");
            m.checkInvariants();
        });

        test("null keys rejected on put, tolerated on reads", () -> {
            CuckooHashMap<String, Integer> m = CuckooHashMap.standard();
            try {
                m.put(null, 1);
                throw new AssertionError("expected NullPointerException");
            } catch (NullPointerException expected) {
            }
            eq(null, m.get(null), "get(null)");
            check(!m.containsKey(null), "containsKey(null)");
            eq(null, m.remove(null), "remove(null)");
        });

        test("null values are stored and distinguishable via containsKey", () -> {
            CuckooHashMap<String, Integer> m = CuckooHashMap.standard();
            m.put("x", null);
            check(m.containsKey("x"), "containsKey for null value");
            eq(null, m.get("x"), "get returns null value");
            eq(1, m.size(), "size");
            eq(null, m.remove("x"), "remove returns null value");
            check(!m.containsKey("x"), "removed");
            eq(0, m.size(), "size after remove");
            m.checkInvariants();
        });

        test("remove of missing key leaves map unchanged", () -> {
            CuckooHashMap<Integer, Integer> m = CuckooHashMap.standard();
            for (int i = 0; i < 100; i++) m.put(i, i);
            eq(null, m.remove(1000), "remove missing");
            eq(100, m.size(), "size unchanged");
            m.checkInvariants();
        });

        test("equal-but-not-identical keys are found", () -> {
            CuckooHashMap<String, Integer> m = CuckooHashMap.standard();
            m.put(new String("hello"), 7);
            eq(7, m.get(new String("hello")), "lookup via distinct String instance");
        });

        test("growth from a single bucket to one million keys", () -> {
            CuckooHashMap<Integer, Integer> m = CuckooHashMap.builder().bucketsPerTable(1).build();
            int n = 1_000_000;
            for (int i = 0; i < n; i++) m.put(i, -i);
            eq(n, m.size(), "size");
            for (int i = 0; i < n; i++) {
                if (m.get(i) != -i) throw new AssertionError("wrong value for " + i);
            }
            check(m.loadFactor() <= 0.45 + 1e-9, "load factor respected: " + m.loadFactor());
            check(m.growths() > 10, "grew repeatedly");
            m.checkInvariants();
        });

        test("clear empties map and it remains usable", () -> {
            CuckooHashMap<Integer, Integer> m = CuckooHashMap.bucketized();
            for (int i = 0; i < 1000; i++) m.put(i, i);
            m.clear();
            eq(0, m.size(), "size after clear");
            eq(null, m.get(5), "get after clear");
            m.checkInvariants();
            for (int i = 0; i < 1000; i++) m.put(i, i + 1);
            eq(1000, m.size(), "size after refill");
            eq(6, m.get(5), "value after refill");
            m.checkInvariants();
        });

        test("forEach visits every entry exactly once", () -> {
            CuckooHashMap<Integer, Integer> m = CuckooHashMap.bucketized();
            for (int i = 0; i < 5000; i++) m.put(i, i * 2);
            Map<Integer, Integer> seen = new HashMap<>();
            m.forEach((k, v) -> {
                if (seen.put(k, v) != null) throw new AssertionError("visited twice: " + k);
            });
            eq(5000, seen.size(), "visited count");
            for (int i = 0; i < 5000; i++) eq(i * 2, seen.get(i), "value for " + i);
        });

        test("keys sharing one hashCode up to 2b + stash stay in normal mode", () -> {
            for (int b : new int[] {1, 2, 4}) {
                int stash = 4;
                CuckooHashMap<FixedHash, Integer> m = CuckooHashMap.builder()
                        .bucketSize(b).maxLoad(0.9).stash(stash).bucketsPerTable(64).build();
                int fit = 2 * b + stash;
                for (int i = 0; i < fit; i++) m.put(new FixedHash(i, 12345), i);
                check(!m.isDegraded(), "b=" + b + " should not be degraded with " + fit + " colliding keys");
                for (int i = 0; i < fit; i++) eq(i, m.get(new FixedHash(i, 12345)), "b=" + b + " key " + i);
                m.checkInvariants();
            }
        });

        test("more colliding keys than 2b + stash degrade gracefully instead of failing", () -> {
            CuckooHashMap<FixedHash, Integer> m = CuckooHashMap.builder()
                    .bucketSize(1).stash(2).bucketsPerTable(64).build();
            int n = 50;
            for (int i = 0; i < n; i++) m.put(new FixedHash(i, 777), i);
            check(m.isDegraded(), "expected degraded mode");
            eq(n, m.size(), "size");
            for (int i = 0; i < n; i++) eq(i, m.get(new FixedHash(i, 777)), "colliding key " + i);
            m.checkInvariants();
            for (int i = 0; i < 1000; i++) m.put(new FixedHash(1000 + i, 1000 + i), i);
            for (int i = 0; i < n; i++) eq(i, m.get(new FixedHash(i, 777)), "colliding key after mixing " + i);
            for (int i = 0; i < 1000; i++) eq(i, m.get(new FixedHash(1000 + i, 1000 + i)), "normal key " + i);
            m.checkInvariants();
            for (int i = 0; i < n; i++) eq(i, m.remove(new FixedHash(i, 777)), "remove colliding " + i);
            eq(1000, m.size(), "size after removing colliders");
            m.checkInvariants();
        });

        test("real String hashCode collisions (Aa/BB family)", () -> {
            List<String> colliding = new ArrayList<>();
            String[] parts = {"Aa", "BB"};
            for (int mask = 0; mask < 64; mask++) {
                StringBuilder sb = new StringBuilder();
                for (int bit = 0; bit < 6; bit++) sb.append(parts[(mask >> bit) & 1]);
                colliding.add(sb.toString());
            }
            int h = colliding.get(0).hashCode();
            for (String s : colliding) check(s.hashCode() == h, "construction produced collisions");
            CuckooHashMap<String, Integer> m = CuckooHashMap.standard();
            for (int i = 0; i < colliding.size(); i++) m.put(colliding.get(i), i);
            for (int i = 0; i < colliding.size(); i++) eq(i, m.get(colliding.get(i)), colliding.get(i));
            eq(64, m.size(), "size");
            m.checkInvariants();
        });

        test("stash absorbs failed walks at a fixed small table", () -> {
            int failuresWithoutStash = 0;
            int failuresWithStash = 0;
            for (long seed = 0; seed < 300; seed++) {
                CuckooHashMap<Integer, Integer> a = CuckooHashMap.builder()
                        .bucketSize(1).maxLoad(1.0).stash(0).bucketsPerTable(64).seed(seed).build();
                CuckooHashMap<Integer, Integer> b = CuckooHashMap.builder()
                        .bucketSize(1).maxLoad(1.0).stash(4).bucketsPerTable(64).seed(seed).build();
                SplittableRandom r = new SplittableRandom(seed * 31 + 7);
                for (int i = 0; i < 54; i++) {
                    int k = r.nextInt();
                    a.put(k, i);
                    b.put(k, i);
                }
                if (a.rehashes() > 0) failuresWithoutStash++;
                if (b.rehashes() > 0) failuresWithStash++;
                a.checkInvariants();
                b.checkInvariants();
            }
            check(failuresWithoutStash > 0, "expected some failures without a stash at load 0.42 on 128 slots");
            check(failuresWithStash < failuresWithoutStash,
                    "stash should reduce rebuilds: " + failuresWithStash + " vs " + failuresWithoutStash);
        });

        test("remove drains stash back into the table", () -> {
            CuckooHashMap<FixedHash, Integer> m = CuckooHashMap.builder()
                    .bucketSize(1).maxLoad(1.0).stash(4).bucketsPerTable(64).build();
            for (int i = 0; i < 5; i++) m.put(new FixedHash(i, 42), i);
            check(m.stashSize() == 3, "three colliding keys overflow into stash, got " + m.stashSize());
            m.remove(new FixedHash(0, 42));
            m.remove(new FixedHash(1, 42));
            m.checkInvariants();
            eq(1, m.stashSize(), "three survivors, two table slots, so exactly one stays stashed");
            for (int i = 2; i < 5; i++) eq(i, m.get(new FixedHash(i, 42)), "key " + i);
        });

        test("stash never holds a key while a table slot it could use is free", () -> {
            for (long seed = 0; seed < 200; seed++) {
                CuckooHashMap<FixedHash, Integer> m = CuckooHashMap.builder()
                        .bucketSize(1).maxLoad(1.0).stash(4).bucketsPerTable(64).seed(seed).build();
                for (int i = 0; i < 6; i++) m.put(new FixedHash(i, 42), i);
                SplittableRandom r = new SplittableRandom(seed);
                List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5));
                java.util.Collections.shuffle(order, new java.util.Random(r.nextLong()));
                for (int id : order) {
                    m.remove(new FixedHash(id, 42));
                    eq(Math.max(0, m.size() - 2), m.stashSize(), "seed " + seed + " after removing " + id);
                    m.checkInvariants();
                }
            }
        });

        test("cuckoo walks resolve collisions without runaway growth", () -> {
            CuckooHashMap<Integer, Integer> m = CuckooHashMap.builder().bucketsPerTable(1).build();
            SplittableRandom r = new SplittableRandom(99);
            for (int i = 0; i < 500_000; i++) m.put(r.nextInt(), i);
            check(m.loadFactor() > 0.2, "table grew far beyond what the load factor requires: load " + m.loadFactor());
            check(m.rehashes() < 10, "too many same-size rehashes: " + m.rehashes());
        });

        test("a failed walk is usually fixed by rehashing at the same size", () -> {
            int trialsWithRehash = 0;
            int trialsThatGrew = 0;
            for (long seed = 0; seed < 400; seed++) {
                CuckooHashMap<Integer, Integer> m = CuckooHashMap.builder()
                        .bucketSize(1).maxLoad(1.0).stash(0).bucketsPerTable(64).seed(seed).build();
                SplittableRandom r = new SplittableRandom(seed * 17 + 3);
                for (int i = 0; i < 50; i++) m.put(r.nextInt(), i);
                if (m.rehashes() > 0) {
                    trialsWithRehash++;
                    if (m.capacity() > 128) trialsThatGrew++;
                }
            }
            check(trialsWithRehash > 0, "expected at least some failed walks");
            check(trialsThatGrew * 2 < trialsWithRehash,
                    "fresh hash seeds should fix most failures without growing: grew in "
                            + trialsThatGrew + " of " + trialsWithRehash);
        });

        test("a key mutated after insertion is no longer findable (documented contract)", () -> {
            CuckooHashMap<MutableKey, Integer> m = CuckooHashMap.standard();
            MutableKey k = new MutableKey(1);
            m.put(k, 1);
            k.value = 999_999;
            check(!m.containsKey(k), "mutated key should not be found under its new hash");
        });

        test("builder rejects invalid configuration", () -> {
            int rejected = 0;
            try { CuckooHashMap.builder().bucketSize(0).build(); } catch (IllegalArgumentException e) { rejected++; }
            try { CuckooHashMap.builder().maxLoad(0).build(); } catch (IllegalArgumentException e) { rejected++; }
            try { CuckooHashMap.builder().maxLoad(1.5).build(); } catch (IllegalArgumentException e) { rejected++; }
            try { CuckooHashMap.builder().stash(-1).build(); } catch (IllegalArgumentException e) { rejected++; }
            try { CuckooHashMap.builder().maxKicks(-1).build(); } catch (IllegalArgumentException e) { rejected++; }
            eq(5, rejected, "invalid configs rejected");
        });

        for (Config cfg : CONFIGS) {
            test("differential vs HashMap, small key space churn [" + cfg.name() + "]", () ->
                    differential(cfg, 20_000, 200_000, 500, 11));
            test("differential vs HashMap, large key space [" + cfg.name() + "]", () ->
                    differential(cfg, Integer.MAX_VALUE, 200_000, 5_000, 23));
        }
    }

    private static void differential(Config cfg, int keySpace, int ops, int checkEvery, long seed) {
        for (long trial = 0; trial < 3; trial++) {
            SplittableRandom r = new SplittableRandom(seed + trial * 1_000_003);
            CuckooHashMap<Integer, Integer> m = cfg.build(seed ^ trial);
            HashMap<Integer, Integer> ref = new HashMap<>();
            for (int op = 0; op < ops; op++) {
                int key = keySpace == Integer.MAX_VALUE ? r.nextInt() : r.nextInt(keySpace);
                int choice = r.nextInt(100);
                if (choice < 50) {
                    Integer value = r.nextInt(10) == 0 ? null : r.nextInt();
                    eq(ref.put(key, value), m.put(key, value), "put(" + key + ") at op " + op);
                } else if (choice < 75) {
                    eq(ref.remove(key), m.remove(key), "remove(" + key + ") at op " + op);
                } else if (choice < 90) {
                    eq(ref.get(key), m.get(key), "get(" + key + ") at op " + op);
                } else {
                    eq(ref.containsKey(key), m.containsKey(key), "containsKey(" + key + ") at op " + op);
                }
                eq(ref.size(), m.size(), "size at op " + op);
                if (op % checkEvery == 0) m.checkInvariants();
            }
            m.checkInvariants();
            for (Map.Entry<Integer, Integer> e : ref.entrySet()) {
                check(m.containsKey(e.getKey()), "missing key " + e.getKey());
                eq(e.getValue(), m.get(e.getKey()), "final value for " + e.getKey());
            }
            int[] count = {0};
            m.forEach((k, v) -> {
                count[0]++;
                check(ref.containsKey(k), "extra key " + k);
            });
            eq(ref.size(), count[0], "forEach count");
        }
    }

    public static void main(String[] args) {
        int passed = 0;
        int failed = 0;
        long start = System.nanoTime();
        for (Case c : CASES) {
            long t = System.nanoTime();
            try {
                c.body().run();
                passed++;
                System.out.printf("PASS  %-78s %6d ms%n", c.name(), (System.nanoTime() - t) / 1_000_000);
            } catch (Throwable e) {
                failed++;
                System.out.printf("FAIL  %s%n      %s%n", c.name(), e);
            }
        }
        System.out.printf("%n%d passed, %d failed in %.1f s%n", passed, failed, (System.nanoTime() - start) / 1e9);
        if (failed > 0) System.exit(1);
    }
}

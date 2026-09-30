package cuckoo.bench;

import cuckoo.CuckooHashMap;

import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;

public final class Bench {

    private static final int WARMUP = 3;
    private static final int TRIALS = 7;
    private static long sink;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        switch (args[0]) {
            case "lookup" -> lookup(args[1], args[2], ints(args[3]));
            case "insert" -> insert(args[1], args[2], ints(args[3]));
            case "latency" -> latency(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "memory" -> memory(args[1], ints(args[2]));
            case "fill" -> fill(ints(args[1]), ints(args[2]), Integer.parseInt(args[3]), ints(args[4]));
            case "kicks" -> kicks(ints(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "loadsweep" -> loadSweep(ints(args[1]), Integer.parseInt(args[2]), args[3]);
            case "stash" -> stash(ints(args[1]), ints(args[2]), Double.parseDouble(args[3]), Long.parseLong(args[4]));
            default -> throw new IllegalArgumentException("unknown experiment " + args[0]);
        }
        if (sink == 42) System.err.println("sink " + sink);
    }

    private static int[] ints(String csv) {
        return Arrays.stream(csv.split(",")).mapToInt(Integer::parseInt).toArray();
    }

    private static long lookups(Table t, Integer[] q) {
        long s = 0;
        for (Integer k : q) {
            Integer v = t.get(k);
            s += v == null ? 1 : v;
        }
        return s;
    }

    private static void lookup(String impl, String dist, int[] sizes) {
        for (int n : sizes) {
            Integer[] present = Keys.present(dist, n);
            Integer[] absent = Keys.absent(dist, n);
            Table t = Table.create(impl);
            for (Integer k : present) t.put(k, k);
            int qn = Math.max(1 << 21, n);
            Integer[] hits = Keys.queries(dist, present, qn, n);
            Integer[] misses = Keys.queries(dist, absent, qn, n + 1);
            for (int i = 0; i < WARMUP; i++) {
                sink += lookups(t, hits);
                sink += lookups(t, misses);
            }
            for (int trial = 0; trial < TRIALS; trial++) {
                long t0 = System.nanoTime();
                sink += lookups(t, hits);
                long t1 = System.nanoTime();
                sink += lookups(t, misses);
                long t2 = System.nanoTime();
                System.out.printf("%s,%s,%d,hit,%d,%.3f%n", impl, dist, n, trial, (t1 - t0) / (double) qn);
                System.out.printf("%s,%s,%d,miss,%d,%.3f%n", impl, dist, n, trial, (t2 - t1) / (double) qn);
            }
            System.out.flush();
        }
    }

    private static void insert(String impl, String dist, int[] sizes) {
        for (int n : sizes) {
            Integer[] keys = Keys.present(dist, n);
            int reps = Math.max(1, (1 << 20) / n);
            for (int round = 0; round < WARMUP + TRIALS; round++) {
                Table last = null;
                long t0 = System.nanoTime();
                for (int r = 0; r < reps; r++) {
                    Table t = Table.create(impl);
                    for (Integer k : keys) t.put(k, k);
                    sink += t.size();
                    last = t;
                }
                long dt = System.nanoTime() - t0;
                if (round >= WARMUP) {
                    System.out.printf("%s,%s,%d,%d,%.3f,%s%n", impl, dist, n, round - WARMUP,
                            dt / (double) (reps * (long) n), last.stats());
                }
            }
            System.out.flush();
        }
    }

    private static void latency(String impl, int n, int trials) {
        Integer[] keys = Keys.present("random", n);
        long[] d = new long[n];
        for (int trial = 0; trial < trials + 1; trial++) {
            Table t = Table.create(impl);
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                t.put(keys[i], keys[i]);
                d[i] = System.nanoTime() - t0;
            }
            sink += t.size();
            if (trial == 0) continue;
            long total = 0;
            for (long x : d) total += x;
            for (int i = 0; i < n; i++) {
                if (d[i] >= 50_000) System.out.printf("spike,%s,%d,%d,%.1f%n", impl, trial, i, d[i] / 1000.0);
            }
            long[] sorted = d.clone();
            Arrays.sort(sorted);
            System.out.printf("pct,%s,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f%n", impl, trial,
                    total / (double) n,
                    sorted[n / 2] / 1.0,
                    sorted[(int) (n * 0.99)] / 1.0,
                    sorted[(int) (n * 0.999)] / 1.0,
                    sorted[(int) (n * 0.9999)] / 1.0,
                    sorted[n - 1] / 1000.0,
                    top(d, 0.001) * 100.0);
            System.out.flush();
        }
    }

    private static double top(long[] d, double frac) {
        long[] s = d.clone();
        Arrays.sort(s);
        long all = 0;
        long tail = 0;
        int cut = (int) (s.length * (1 - frac));
        for (int i = 0; i < s.length; i++) {
            all += s[i];
            if (i >= cut) tail += s[i];
        }
        return tail / (double) all;
    }

    private static long usedHeap() throws InterruptedException {
        Runtime rt = Runtime.getRuntime();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(20);
            best = Math.min(best, rt.totalMemory() - rt.freeMemory());
        }
        return best;
    }

    private static void memory(String impl, int[] sizes) throws InterruptedException {
        for (int n : sizes) {
            Integer[] keys = Keys.present("random", n);
            long before = usedHeap();
            Table t = Table.create(impl);
            for (Integer k : keys) t.put(k, k);
            long after = usedHeap();
            sink += t.size() + keys.length;
            System.out.printf("%s,%d,%.2f,%s%n", impl, n, (after - before) / (double) n, t.stats());
            System.out.flush();
        }
    }

    private static void fill(int[] bucketSizes, int[] slotCounts, int trials, int[] maxKicks) {
        for (int mk : maxKicks) {
            for (int b : bucketSizes) {
                for (int slots : slotCounts) {
                    for (int trial = 0; trial < trials; trial++) {
                        long seed = 1_000_003L * trial + 31L * slots + b + 7L * mk;
                        CuckooHashMap<Integer, Integer> m = CuckooHashMap.builder()
                                .bucketSize(b).maxLoad(1.0).stash(0).maxKicks(mk)
                                .bucketsPerTable(slots / (2 * b)).seed(seed).build();
                        SplittableRandom r = new SplittableRandom(seed ^ 0xABCDEFL);
                        int inserted = 0;
                        while (m.rehashes() == 0 && inserted < slots) {
                            m.put(r.nextInt(), inserted);
                            inserted = m.size();
                        }
                        double failLoad = m.rehashes() == 0 ? 1.0 : (inserted - 1) / (double) slots;
                        System.out.printf("%d,%d,%d,%d,%.5f%n", mk, b, slots, trial, failLoad);
                    }
                    System.out.flush();
                }
            }
        }
    }

    private static void kicks(int[] bucketSizes, int slots, int trials) {
        int bins = 100;
        for (int b : bucketSizes) {
            double[] sum = new double[bins];
            long[] cnt = new long[bins];
            long[] maxKick = new long[bins];
            for (int trial = 0; trial < trials; trial++) {
                long seed = 7919L * trial + b;
                CuckooHashMap<Integer, Integer> m = CuckooHashMap.builder()
                        .bucketSize(b).maxLoad(1.0).stash(0).maxKicks(1000)
                        .bucketsPerTable(slots / (2 * b)).seed(seed).build();
                SplittableRandom r = new SplittableRandom(~seed);
                while (m.rehashes() == 0 && m.size() < slots) {
                    int bin = (int) (100.0 * m.size() / slots);
                    long k0 = m.kicks();
                    m.put(r.nextInt(), 0);
                    if (m.rehashes() > 0) break;
                    long dk = m.kicks() - k0;
                    sum[bin] += dk;
                    cnt[bin]++;
                    maxKick[bin] = Math.max(maxKick[bin], dk);
                }
            }
            for (int i = 0; i < bins; i++) {
                if (cnt[i] > 0) {
                    System.out.printf("%d,%.2f,%.5f,%d,%d%n", b, i / 100.0, sum[i] / cnt[i], maxKick[i], cnt[i]);
                }
            }
            System.out.flush();
        }
    }

    private static void loadSweep(int[] bucketSizes, int slots, String loadsCsv) {
        double[] loads = Arrays.stream(loadsCsv.split(",")).mapToDouble(Double::parseDouble).toArray();
        int qn = 1 << 22;
        for (int b : bucketSizes) {
            for (double load : loads) {
                if (load > (b == 1 ? 0.49 : b == 2 ? 0.87 : 0.96)) continue;
                int n = (int) (load * slots);
                CuckooHashMap<Integer, Integer> m = CuckooHashMap.builder()
                        .bucketSize(b).maxLoad(1.0).stash(4).maxKicks(2000)
                        .bucketsPerTable(slots / (2 * b)).seed(12345L + b).build();
                Integer[] present = Keys.present("random", n);
                Integer[] absent = Keys.absent("random", n);
                for (Integer k : present) m.put(k, k);
                if (m.capacity() != slots) continue;
                Integer[] hits = Keys.queries("random", present, qn, n);
                Integer[] misses = Keys.queries("random", absent, qn, n + 1);
                for (int round = 0; round < WARMUP + 5; round++) {
                    long t0 = System.nanoTime();
                    long s = 0;
                    for (Integer k : hits) s += m.containsKey(k) ? 1 : 0;
                    long t1 = System.nanoTime();
                    for (Integer k : misses) s += m.containsKey(k) ? 1 : 0;
                    long t2 = System.nanoTime();
                    sink += s;
                    if (round >= WARMUP) {
                        int trial = round - WARMUP;
                        System.out.printf("%d,%d,%.4f,hit,%d,%.3f%n", b, slots, m.loadFactor(), trial, (t1 - t0) / (double) qn);
                        System.out.printf("%d,%d,%.4f,miss,%d,%.3f%n", b, slots, m.loadFactor(), trial, (t2 - t1) / (double) qn);
                    }
                }
                System.out.flush();
            }
        }
    }

    private static void stash(int[] slotCounts, int[] stashSizes, double load, long trialBudget) {
        for (int s : stashSizes) {
            for (int slots : slotCounts) {
                long trials = trialBudget / slots;
                int target = (int) (load * slots);
                long failures = 0;
                SplittableRandom r = new SplittableRandom(slots * 131L + s);
                CuckooHashMap<Integer, Integer> m = null;
                for (long t = 0; t < trials; t++) {
                    if (m == null || m.rehashes() > 0) {
                        m = CuckooHashMap.builder().bucketSize(1).maxLoad(1.0).stash(s).maxKicks(1000)
                                .bucketsPerTable(slots / 2).seed(r.nextLong()).build();
                    } else {
                        m.clear();
                    }
                    while (m.size() < target) m.put(r.nextInt(), 0);
                    if (m.rehashes() > 0) failures++;
                }
                System.out.printf("%d,%d,%.2f,%d,%d,%.3e%n", s, slots, load, trials, failures,
                        failures / (double) trials);
                System.out.flush();
            }
        }
    }
}

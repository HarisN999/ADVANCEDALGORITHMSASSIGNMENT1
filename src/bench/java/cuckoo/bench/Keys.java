package cuckoo.bench;

import java.util.SplittableRandom;

final class Keys {

    private Keys() {}

    static int scramble(int x) {
        x *= 0x9E3779B1;
        x ^= x >>> 15;
        x *= 0x2C1B3C6D;
        x ^= x >>> 12;
        x *= 0x297A2D39;
        x ^= x >>> 15;
        return x;
    }

    static Integer[] present(String dist, int n) {
        Integer[] keys = new Integer[n];
        for (int i = 0; i < n; i++) keys[i] = dist.equals("sequential") ? i : scramble(i);
        return keys;
    }

    static Integer[] absent(String dist, int n) {
        Integer[] keys = new Integer[n];
        for (int i = 0; i < n; i++) keys[i] = dist.equals("sequential") ? n + i : scramble(n + i);
        return keys;
    }

    static Integer[] queries(String dist, Integer[] source, int count, long seed) {
        Integer[] q = new Integer[count];
        if (dist.equals("sequential")) {
            for (int i = 0; i < count; i++) q[i] = source[i % source.length];
        } else {
            SplittableRandom r = new SplittableRandom(seed);
            for (int i = 0; i < count; i++) q[i] = source[r.nextInt(source.length)];
        }
        return q;
    }
}

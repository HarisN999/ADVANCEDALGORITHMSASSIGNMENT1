package cuckoo.bench;

import cuckoo.CuckooHashMap;

import java.util.HashMap;

interface Table {
    Integer get(Integer k);

    void put(Integer k, Integer v);

    int size();

    String stats();

    static Table create(String impl) {
        return switch (impl) {
            case "hashmap" -> new JdkTable();
            case "cuckoo1" -> new CuckooTable(CuckooHashMap.standard());
            case "cuckoo4" -> new CuckooTable(CuckooHashMap.bucketized());
            default -> throw new IllegalArgumentException("unknown impl " + impl);
        };
    }

    final class JdkTable implements Table {
        private final HashMap<Integer, Integer> map = new HashMap<>();

        public Integer get(Integer k) { return map.get(k); }
        public void put(Integer k, Integer v) { map.put(k, v); }
        public int size() { return map.size(); }
        public String stats() { return ",,,,"; }
    }

    final class CuckooTable implements Table {
        private final CuckooHashMap<Integer, Integer> map;

        CuckooTable(CuckooHashMap<Integer, Integer> map) { this.map = map; }

        public Integer get(Integer k) { return map.get(k); }
        public void put(Integer k, Integer v) { map.put(k, v); }
        public int size() { return map.size(); }

        public String stats() {
            return map.kicks() + "," + map.rehashes() + "," + map.growths() + ","
                    + map.capacity() + "," + String.format("%.4f", map.loadFactor());
        }
    }
}

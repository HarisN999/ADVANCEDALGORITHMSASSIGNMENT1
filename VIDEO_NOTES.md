# Video walkthrough notes (3–5 minutes)

This is a speaking guide, not a script. Say it in your own words with the code open. Read the code first until you can explain each point without these notes. The marker is testing exactly that.

Line numbers refer to `src/main/java/cuckoo/CuckooHashMap.java`.

---

## 1. Where the core lives (≈45 s)

- Everything is in one class, `CuckooHashMap`. The tests and the benchmark harness are separate source trees.
- **Layout.** There are three parallel arrays: `keys`, `vals`, and `hashes`. They are split into two halves, table 0 and table 1. Each table has `tableBuckets` buckets of `bucketSize` slots. `bucketStart(table, bucket)` (line 210) turns a (table, bucket) pair into an array index.
- **Two hash functions.** `index0` and `index1` (line 202) run the key's `hashCode()` through a mixing function (`mix`, line 214) with two different random seeds.
- **The stash** is a tiny overflow list, 4 entries by default, for keys that can't be placed.
- **Flow of `put`** (line 133): if the key already exists, overwrite it. If the load would exceed `maxLoad`, double the table first. Then `insertNew` → `cuckooWalk`.

## 2. Tricky part: the eviction walk and the rebuild (≈60 s)

Show `cuckooWalk` (line 260).

- First it tries an empty slot in either of the key's two buckets.
- If both are full, it picks a victim, swaps the new key in, and the victim becomes the key that needs a home.
- The victim goes to *its other* bucket. Line 280 is the subtle bit. The victim was just evicted from bucket `current`, so its alternative is in the *other* table: if `current` is in table 0's half of the array, go to `index1`, otherwise `index0`.
- After `maxKicks` swaps it gives up. The key left holding the bag might not be the one we started inserting. It goes to the stash, and if the stash is full the whole table is rebuilt (`rebuild`, line 312) with fresh seeds.

Why it was hard, and a good thing to be honest about in the video: a rebuild can itself fail. The first version threw an exception if a rebuild failed repeatedly, and that loses data mid-rebuild. The fix was a *degraded mode* (line 357): if many keys share one `hashCode`, fresh seeds can never separate them, so those keys overflow into a growable stash instead. The test "real String hashCode collisions (Aa/BB family)" exercises exactly this.

## 3. The invariant (≈60 s)

> **Every key in the main table sits in bucket `index0(h)` of table 0 or bucket `index1(h)` of table 1, where `h` is the key's stored hash. Every other key is in the stash.**

- **Established:** every write to the table goes through `placeInEmpty`, and callers only pass bucket starts computed from the key's own hash (lines 261–264 and 280–283).
- **Relied on:** `findSlot` (line 223) looks in exactly those two buckets plus the stash, and nowhere else. That's why lookups are worst-case constant: two buckets plus at most 4 stash slots, no matter how full the table is.
- **Checked:** `checkInvariants()` (line 450) recomputes every key's expected bucket and asserts it matches its actual position. The differential tests call it every few hundred random operations.
- A second invariant worth mentioning: `hashes[i] == keys[i].hashCode()`.

## 4. What breaks (≈60 s). Pick one

**Option A, line 274 (`hashes[victim] = h;`).** Delete it. The key moves to a new slot but its stored hash stays behind with the old occupant's value. `findSlot` compares stored hashes *before* calling `equals`, so the moved key becomes invisible to lookups. `scripts/mutation_check.sh` shows the tests catch this immediately.

**Option B, line 280 (the alternate-bucket choice). This is the more interesting one.** Swap the two branches so the victim is re-inserted into the *same* table it was just evicted from. Nothing ever becomes incorrect: every key is still in one of its two legal buckets. But the walk now ping-pongs inside one bucket, every non-trivial insert fails, and the table rebuilds and grows over and over. In the test run the table ended up at a load factor of **0.015 instead of ≈0.24**, about 16× too much memory. The original tests *didn't catch it* because they only checked answers, not efficiency. That's why the "no runaway growth" test exists. It's a good "what I learned" point too.

---

Quick self-check before recording. Can you answer these without looking?

1. Why does a lookup never need to probe more than two buckets?
2. Why can't fresh seeds help when 10 keys have the same `hashCode()`?
3. Why is the b=1 table capped at load 0.45 but the b=4 table at 0.90?
4. Why does `remove` need no tombstones, unlike linear probing?

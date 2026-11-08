package com.wraithhawit.reciperoulette;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** The seeded item-to-item mapping. Plain Java so it can be unit tested. */
public final class Shuffle {
    private Shuffle() {
    }

    /**
     * Maps every id in {@code pool} to a different id in {@code pool}, as one big cycle: each id
     * gets a pseudo-random key from (seed, id), the ids are ordered by key, and each maps to the
     * next one (the last wraps to the first). So no item maps to itself and every item is the
     * target of exactly one other.
     *
     * <p>Keys depend only on the seed and the id itself, never on what else is in the pool. Adding
     * or removing an item (a mod update, a mod regenerating its recipes) changes one other
     * item's target, not the whole scramble. A plain shuffle of a sorted list would reshuffle
     * everything whenever the pool changes by one.
     */
    public static Map<String, String> derangement(Collection<String> pool, long seed) {
        Map<String, String> map = new HashMap<>();
        List<String> ids = new ArrayList<>(new TreeSet<>(pool));
        int n = ids.size();
        if (n < 2) {
            return map;
        }
        long seedMix = mix(seed);
        Map<String, Long> keys = new HashMap<>(n * 2);
        for (String id : ids) {
            keys.put(id, mix(fnv1a64(id) ^ seedMix));
        }
        // Ties between 64-bit keys are astronomically rare; the id breaks them deterministically.
        ids.sort(Comparator.<String>comparingLong(keys::get).thenComparing(Comparator.naturalOrder()));
        for (int i = 0; i < n; i++) {
            map.put(ids.get(i), ids.get((i + 1) % n));
        }
        return map;
    }

    private static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** SplitMix64 finalizer: spreads every input bit across the output. */
    private static long mix(long z) {
        z += 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}

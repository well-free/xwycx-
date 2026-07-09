package org.example.infrastructure.cache;

import org.springframework.stereotype.Service;

import java.util.BitSet;
import java.util.Locale;

@Service
public class BloomFilterService {
    private static final int SIZE = 1 << 20;
    private static final int HASH_COUNT = 3;
    private final BitSet bitSet = new BitSet(SIZE);

    public void put(String value) {
        String normalized = normalize(value);
        for (int i = 0; i < HASH_COUNT; i++) {
            bitSet.set(hash(normalized, i));
        }
    }

    public boolean mightContain(String value) {
        String normalized = normalize(value);
        for (int i = 0; i < HASH_COUNT; i++) {
            if (!bitSet.get(hash(normalized, i))) {
                return false;
            }
        }
        return true;
    }

    private int hash(String value, int seed) {
        int hash = value.hashCode() * 31 + seed * 131;
        return Math.floorMod(hash, SIZE);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

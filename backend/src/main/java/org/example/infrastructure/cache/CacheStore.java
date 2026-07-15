package org.example.infrastructure.cache;

import java.time.Duration;
import java.util.Optional;

public interface CacheStore {
    void put(String key, String value, Duration ttl);

    boolean putIfAbsent(String key, String value, Duration ttl);

    Optional<String> get(String key);

    void evict(String key);
}

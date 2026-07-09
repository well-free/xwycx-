package org.example.infrastructure.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalRedisCacheService implements CacheStore {
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public void put(String key, String value, Duration ttl) {
        cache.put(key, new Entry(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    public Optional<String> get(String key) {
        Entry entry = cache.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expireAtMillis < System.currentTimeMillis()) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value);
    }

    public void evict(String key) {
        cache.remove(key);
    }

    private record Entry(String value, long expireAtMillis) {
    }
}

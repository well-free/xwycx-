package org.example.infrastructure.rate;

import org.example.web.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalRateLimitService implements RateLimitService {
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void acquire(String key, long capacity, long refillTokens) {
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(capacity, Instant.now().toEpochMilli()));
        synchronized (bucket) {
            long now = Instant.now().toEpochMilli();
            long elapsed = Math.max(0L, now - bucket.lastRefillMillis);
            long refill = elapsed / 1000L * refillTokens;
            if (refill > 0) {
                bucket.tokens = Math.min(capacity, bucket.tokens + refill);
                bucket.lastRefillMillis = now;
            }
            if (bucket.tokens <= 0) {
                throw BusinessException.tooManyRequests("too many requests");
            }
            bucket.tokens--;
        }
    }

    private static final class Bucket {
        private long tokens;
        private long lastRefillMillis;

        private Bucket(long tokens, long lastRefillMillis) {
            this.tokens = tokens;
            this.lastRefillMillis = lastRefillMillis;
        }
    }
}

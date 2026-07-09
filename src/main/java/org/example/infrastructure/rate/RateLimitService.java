package org.example.infrastructure.rate;

public interface RateLimitService {
    void acquire(String key, long capacity, long refillTokens);
}

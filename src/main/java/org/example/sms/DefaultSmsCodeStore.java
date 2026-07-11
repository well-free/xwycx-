package org.example.sms;

import org.example.config.AppProperties;
import org.example.infrastructure.cache.CacheStore;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class DefaultSmsCodeStore implements SmsCodeStore {
    private static final String CODE_PREFIX = "sms:code:";

    private final CacheStore cacheStore;
    private final AppProperties properties;

    public DefaultSmsCodeStore(CacheStore cacheStore, AppProperties properties) {
        this.cacheStore = cacheStore;
        this.properties = properties;
    }

    @Override
    public void save(String phone, String code) {
        cacheStore.put(CODE_PREFIX + phone, code, Duration.ofSeconds(properties.getSms().getCodeTtlSeconds()));
    }

    @Override
    public boolean consume(String phone, String code) {
        String key = CODE_PREFIX + phone;
        boolean matched = cacheStore.get(key)
                .filter(code::equals)
                .isPresent();
        if (matched) {
            cacheStore.evict(key);
        }
        return matched;
    }
}

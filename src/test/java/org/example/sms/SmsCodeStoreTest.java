package org.example.sms;

import org.example.config.AppProperties;
import org.example.infrastructure.cache.LocalRedisCacheService;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SmsCodeStoreTest {
    @Test
    void shouldConsumeSmsCodeOnlyOnce() {
        AppProperties properties = new AppProperties();
        LocalRedisCacheService cacheStore = new LocalRedisCacheService();
        DefaultSmsCodeStore store = new DefaultSmsCodeStore(cacheStore, properties);

        store.save("13800000010", "654321");

        assertThat(store.consume("13800000010", "654321")).isTrue();
        assertThat(store.consume("13800000010", "654321")).isFalse();
    }

    @Test
    void shouldGenerateSixDigitSmsCode() {
        String code = SmsCodeGenerator.generate(6);

        assertThat(code).matches("\\d{6}");
    }

    @Test
    void shouldNotOverwriteNewerCodeWhenRestoringConsumedCode() {
        AppProperties properties = new AppProperties();
        LocalRedisCacheService cacheStore = new LocalRedisCacheService();
        DefaultSmsCodeStore store = new DefaultSmsCodeStore(cacheStore, properties);
        store.save("13800000030", "111111");
        assertThat(store.consume("13800000030", "111111")).isTrue();
        store.save("13800000030", "222222");

        boolean restored = store.restoreIfAbsent("13800000030", "111111", Duration.ofSeconds(30));

        assertThat(restored).isFalse();
        assertThat(store.consume("13800000030", "222222")).isTrue();
    }
}

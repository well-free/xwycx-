package org.example.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    @Bean
    @ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
    public Object redissonClient() {
        try {
            Class<?> configClass = Class.forName("org.redisson.config.Config");
            Object config = configClass.getConstructor().newInstance();
            Object singleServerConfig = configClass.getMethod("useSingleServer").invoke(config);
            singleServerConfig.getClass().getMethod("setAddress", String.class)
                    .invoke(singleServerConfig, "redis://localhost:6379");
            Class<?> redissonClass = Class.forName("org.redisson.Redisson");
            return redissonClass.getMethod("create", configClass).invoke(null, config);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("failed to create Redisson client", ex);
        }
    }
}

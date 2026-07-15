package org.example.infrastructure.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;

@Service
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
public class RedisCacheService implements CacheStore {
    private final ApplicationContext applicationContext;

    public RedisCacheService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        Object valueOps = opsForValue();
        invoke(valueOps, "set", new Class<?>[]{Object.class, Object.class, Duration.class}, key, value, ttl);
    }

    @Override
    public boolean putIfAbsent(String key, String value, Duration ttl) {
        Object inserted = invoke(opsForValue(), "setIfAbsent",
                new Class<?>[]{Object.class, Object.class, Duration.class}, key, value, ttl);
        return Boolean.TRUE.equals(inserted);
    }

    @Override
    public Optional<String> get(String key) {
        Object value = invoke(opsForValue(), "get", new Class<?>[]{Object.class}, key);
        return Optional.ofNullable(value == null ? null : value.toString());
    }

    @Override
    public void evict(String key) {
        invoke(redisTemplate(), "delete", new Class<?>[]{Object.class}, key);
    }

    private Object redisTemplate() {
        return applicationContext.getBean("stringRedisTemplate");
    }

    private Object opsForValue() {
        return invoke(redisTemplate(), "opsForValue", new Class<?>[]{});
    }

    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("failed to invoke Redis operation", ex);
        }
    }
}

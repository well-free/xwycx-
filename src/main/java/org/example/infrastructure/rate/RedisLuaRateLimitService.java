package org.example.infrastructure.rate;

import org.example.web.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
public class RedisLuaRateLimitService implements RateLimitService {
    private static final String SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local state = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end
            local elapsed = math.max(0, now - ts)
            local add = math.floor(elapsed / 1000 * refill)
            if add > 0 then
              tokens = math.min(capacity, tokens + add)
              ts = now
            end
            if tokens <= 0 then
              redis.call('HMSET', key, 'tokens', tokens, 'ts', ts)
              redis.call('EXPIRE', key, 60)
              return 0
            end
            tokens = tokens - 1
            redis.call('HMSET', key, 'tokens', tokens, 'ts', ts)
            redis.call('EXPIRE', key, 60)
            return 1
            """;

    private final ApplicationContext applicationContext;

    public RedisLuaRateLimitService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void acquire(String key, long capacity, long refillTokens) {
        Long result = executeScript(List.of("rate:" + key),
                String.valueOf(capacity), String.valueOf(refillTokens), String.valueOf(System.currentTimeMillis()));
        if (result == null || result == 0L) {
            throw BusinessException.tooManyRequests("too many requests");
        }
    }

    private Long executeScript(List<String> keys, String capacity, String refillTokens, String now) {
        try {
            Class<?> scriptClass = Class.forName("org.springframework.data.redis.core.script.DefaultRedisScript");
            Object script = scriptClass.getConstructor(String.class, Class.class).newInstance(SCRIPT, Long.class);
            Object redisTemplate = applicationContext.getBean("stringRedisTemplate");
            Method execute = redisTemplate.getClass().getMethod("execute", scriptClass, List.class, Object[].class);
            Object result = execute.invoke(redisTemplate, script, keys, new Object[]{capacity, refillTokens, now});
            return result == null ? null : ((Number) result).longValue();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("failed to invoke Redis Lua rate limiter", ex);
        }
    }
}

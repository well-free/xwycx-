package org.example.infrastructure.lock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@ConditionalOnBean(name = "redissonClient")
public class OrderLockService implements DistributedLockService {
    private final Object redissonClient;

    public OrderLockService(@Qualifier("redissonClient") Object redissonClient) {
        this.redissonClient = redissonClient;
    }

    public <T> T withLock(String key, Supplier<T> action) {
        Object lock = invoke(redissonClient, "getLock", new Class<?>[]{String.class}, key);
        boolean acquired = false;
        try {
            acquired = (boolean) invoke(lock, "tryLock",
                    new Class<?>[]{long.class, TimeUnit.class},
                    3L, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException("lock busy");
            }
            return action.get();
        } finally {
            if (acquired && (boolean) invoke(lock, "isHeldByCurrentThread", new Class<?>[]{})) {
                invoke(lock, "unlock", new Class<?>[]{});
            }
        }
    }

    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("lock interrupted", interruptedException);
            }
            throw new IllegalStateException("failed to invoke Redisson lock", cause);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("failed to invoke Redisson lock", ex);
        }
    }
}

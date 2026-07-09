package org.example.infrastructure.lock;

import java.util.function.Supplier;

public interface DistributedLockService {
    <T> T withLock(String key, Supplier<T> action);
}

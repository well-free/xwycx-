package org.example.infrastructure.lock;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OrderLockServiceTest {
    @Test
    void shouldUseWatchdogTryLockWithThreeSecondWait() {
        FakeRedissonClient client = new FakeRedissonClient();
        OrderLockService service = new OrderLockService(client);

        String result = service.withLock("order:1", () -> "done");

        assertThat(result).isEqualTo("done");
        assertThat(client.lock.waitTime).isEqualTo(3L);
        assertThat(client.lock.timeUnit).isEqualTo(TimeUnit.SECONDS);
        assertThat(client.lock.unlocked).isTrue();
    }

    public static class FakeRedissonClient {
        final FakeLock lock = new FakeLock();

        public FakeLock getLock(String key) {
            return lock;
        }
    }

    public static class FakeLock {
        long waitTime;
        TimeUnit timeUnit;
        boolean unlocked;

        public boolean tryLock(long waitTime, TimeUnit timeUnit) {
            this.waitTime = waitTime;
            this.timeUnit = timeUnit;
            return true;
        }

        public boolean isHeldByCurrentThread() {
            return true;
        }

        public void unlock() {
            unlocked = true;
        }
    }
}

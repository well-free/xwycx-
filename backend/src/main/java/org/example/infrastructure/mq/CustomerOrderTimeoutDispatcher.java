package org.example.infrastructure.mq;

import java.time.Duration;

public interface CustomerOrderTimeoutDispatcher {
    void dispatch(long orderId, Duration delay);
}

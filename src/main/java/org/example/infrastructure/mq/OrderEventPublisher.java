package org.example.infrastructure.mq;

import java.time.Duration;

public interface OrderEventPublisher {
    void sendTimeoutClose(long orderId, Duration delay);

    void sendCatalogChange(long catalogId);
}

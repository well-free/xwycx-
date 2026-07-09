package org.example.infrastructure.mq;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(prefix = "app.mq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopOrderEventPublisher implements OrderEventPublisher {
    @Override
    public void sendTimeoutClose(long orderId, Duration delay) {
        // no-op fallback
    }

    @Override
    public void sendCatalogChange(long catalogId) {
        // no-op fallback
    }
}

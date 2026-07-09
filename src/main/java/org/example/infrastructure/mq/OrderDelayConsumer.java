package org.example.infrastructure.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.mq", name = "enabled", havingValue = "true")
public class OrderDelayConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderDelayConsumer.class);

    @Scheduled(fixedDelay = 30_000)
    public void pollDeadLetterAlarm() {
        log.debug("poll mq dead letter queue");
    }
}

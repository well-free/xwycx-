package org.example.infrastructure.outbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.example.config.AppProperties;
import org.example.infrastructure.mq.CustomerOrderTimeoutDispatcher;
import org.example.infrastructure.mybatis.entity.OutboxEventEntity;
import org.example.infrastructure.mybatis.mapper.OutboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CustomerOrderOutboxProcessor {
    private static final Logger log = LoggerFactory.getLogger(CustomerOrderOutboxProcessor.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxEventMapper outboxEventMapper;
    private final CustomerOrderTimeoutDispatcher timeoutDispatcher;
    private final AppProperties properties;

    public CustomerOrderOutboxProcessor(OutboxEventMapper outboxEventMapper,
                                        CustomerOrderTimeoutDispatcher timeoutDispatcher,
                                        AppProperties properties) {
        this.outboxEventMapper = outboxEventMapper;
        this.timeoutDispatcher = timeoutDispatcher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void processPending() {
        Instant now = Instant.now();
        recoverStaleClaims(now);
        List<OutboxEventEntity> events = outboxEventMapper.selectList(
                new LambdaQueryWrapper<OutboxEventEntity>()
                        .eq(OutboxEventEntity::getStatus, "PENDING")
                        .le(OutboxEventEntity::getAvailableAt, now)
                        .orderByAsc(OutboxEventEntity::getCreatedAt)
                        .last("limit " + BATCH_SIZE));
        events.forEach(this::process);
    }

    private void process(OutboxEventEntity event) {
        Instant claimedAt = Instant.now();
        int claimed = outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEventEntity>()
                .eq(OutboxEventEntity::getId, event.getId())
                .eq(OutboxEventEntity::getStatus, "PENDING")
                .set(OutboxEventEntity::getStatus, "PROCESSING")
                .set(OutboxEventEntity::getUpdatedAt, claimedAt)
                .setSql("attempts = attempts + 1"));
        if (claimed != 1) {
            return;
        }
        try {
            if (!CustomerOrderOutboxService.TIMEOUT_EVENT.equals(event.getEventType())) {
                throw new IllegalArgumentException("unsupported outbox event: " + event.getEventType());
            }
            timeoutDispatcher.dispatch(event.getAggregateId(), Duration.ofMillis(1));
            Instant processedAt = Instant.now();
            outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEventEntity>()
                    .eq(OutboxEventEntity::getId, event.getId())
                    .eq(OutboxEventEntity::getStatus, "PROCESSING")
                    .set(OutboxEventEntity::getStatus, "PROCESSED")
                    .set(OutboxEventEntity::getProcessedAt, processedAt)
                    .set(OutboxEventEntity::getUpdatedAt, processedAt)
                    .set(OutboxEventEntity::getLastError, null));
        } catch (RuntimeException exception) {
            Instant retryAt = Instant.now().plusMillis(properties.getOutbox().getRetryDelayMs());
            outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEventEntity>()
                    .eq(OutboxEventEntity::getId, event.getId())
                    .eq(OutboxEventEntity::getStatus, "PROCESSING")
                    .set(OutboxEventEntity::getStatus, "PENDING")
                    .set(OutboxEventEntity::getAvailableAt, retryAt)
                    .set(OutboxEventEntity::getLastError, truncate(exception.getMessage()))
                    .set(OutboxEventEntity::getUpdatedAt, Instant.now()));
            log.warn("outbox delivery failed eventId={} aggregateId={} retryAt={}",
                    event.getId(), event.getAggregateId(), retryAt, exception);
        }
    }

    private void recoverStaleClaims(Instant now) {
        outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEventEntity>()
                .eq(OutboxEventEntity::getStatus, "PROCESSING")
                .lt(OutboxEventEntity::getUpdatedAt, now.minus(Duration.ofMinutes(5)))
                .set(OutboxEventEntity::getStatus, "PENDING")
                .set(OutboxEventEntity::getAvailableAt, now)
                .set(OutboxEventEntity::getUpdatedAt, now));
    }

    private String truncate(String message) {
        String value = message == null ? "unknown outbox delivery failure" : message;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}

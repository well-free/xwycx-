package org.example.infrastructure.outbox;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.example.infrastructure.mybatis.entity.OutboxEventEntity;
import org.example.infrastructure.mybatis.mapper.OutboxEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;

@Service
public class CustomerOrderOutboxService {
    public static final String AGGREGATE_TYPE = "CUSTOMER_ORDER";
    public static final String TIMEOUT_EVENT = "CUSTOMER_ORDER_TIMEOUT";

    private final OutboxEventMapper outboxEventMapper;

    public CustomerOrderOutboxService(OutboxEventMapper outboxEventMapper) {
        this.outboxEventMapper = outboxEventMapper;
    }

    public void enqueueTimeout(long orderId, Duration delay) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("customer order timeout outbox requires a transaction");
        }
        Instant now = Instant.now();
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(IdWorker.getId());
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(orderId);
        event.setEventType(TIMEOUT_EVENT);
        event.setPayload(Long.toString(orderId));
        event.setStatus("PENDING");
        event.setAvailableAt(now.plus(delay));
        event.setAttempts(0);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        if (outboxEventMapper.insert(event) != 1) {
            throw new IllegalStateException("failed to enqueue customer order timeout");
        }
    }
}

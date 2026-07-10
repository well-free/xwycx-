package org.example.web.dto;

import org.example.refund.RefundStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record RefundResponse(
        long id,
        long paymentId,
        BigDecimal amount,
        String reason,
        RefundStatus status,
        String channelRefundNo,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
}

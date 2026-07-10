package org.example.web.dto;

import org.example.payment.PaymentChannel;
import org.example.payment.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        long id,
        long orderId,
        PaymentChannel channel,
        BigDecimal amount,
        PaymentStatus status,
        String channelTradeNo,
        String payUrl,
        String qrCode,
        Instant createdAt,
        Instant updatedAt,
        Instant expireAt,
        Instant paidAt,
        Instant closedAt) {
}

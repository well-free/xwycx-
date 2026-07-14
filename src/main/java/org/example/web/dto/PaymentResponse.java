package org.example.web.dto;

import org.example.payment.PaymentChannel;
import org.example.payment.MiniProgramPaymentParameters;
import org.example.payment.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        long id,
        long orderId,
        String gatewayMode,
        PaymentChannel channel,
        BigDecimal amount,
        PaymentStatus status,
        String channelTradeNo,
        String payUrl,
        String qrCode,
        String prepayId,
        MiniProgramPaymentParameters miniProgram,
        Instant createdAt,
        Instant updatedAt,
        Instant expireAt,
        Instant paidAt,
        Instant closedAt) {
}

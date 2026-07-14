package org.example.payment;

import java.math.BigDecimal;

public record PaymentGatewayRequest(
        PaymentChannel channel,
        long paymentId,
        BigDecimal amount,
        String payerOpenId) {
}

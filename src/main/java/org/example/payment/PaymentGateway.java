package org.example.payment;

import java.math.BigDecimal;

public interface PaymentGateway {
    PaymentGatewayResult createPayment(PaymentChannel channel, long paymentId, BigDecimal amount);

    boolean verifyCallback(PaymentChannel channel, String signature);

    String createRefundNo(long refundId);
}

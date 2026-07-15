package org.example.payment;

import java.math.BigDecimal;

public interface PaymentGateway {
    PaymentGatewayResult createPayment(PaymentChannel channel, long paymentId, BigDecimal amount);

    default PaymentGatewayResult createPayment(PaymentGatewayRequest request) {
        return createPayment(request.channel(), request.paymentId(), request.amount());
    }

    boolean verifyCallback(PaymentChannel channel, String signature);

    String createRefundNo(long refundId);

    PaymentRefundResult refund(PaymentChannel channel,
                               long refundId,
                               long paymentId,
                               BigDecimal refundAmount,
                               BigDecimal totalAmount,
                               String channelTradeNo);
}

package org.example.wechat;

import org.example.payment.PaymentRefundResult;

import java.math.BigDecimal;

public interface WechatPaySdkClient {
    WechatPayPrepayResult prepay(long paymentId, BigDecimal amount, String payerOpenId);

    PaymentRefundResult refund(long refundId,
                               long paymentId,
                               BigDecimal refundAmount,
                               BigDecimal totalAmount,
                               String channelTradeNo);

    WechatPayNotification verifyNotification(String body,
                                              String serial,
                                              String timestamp,
                                              String nonce,
                                              String signature);
}

package org.example.wechat;

import java.math.BigDecimal;

public record WechatPayNotification(
        long paymentId,
        String notifyId,
        String transactionId,
        BigDecimal amount,
        String status) {
}

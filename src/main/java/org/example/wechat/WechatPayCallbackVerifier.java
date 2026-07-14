package org.example.wechat;

import org.example.web.dto.PaymentCallbackRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.payment.mode", havingValue = "gateway")
public class WechatPayCallbackVerifier {
    private final WechatPaySdkClient client;

    public WechatPayCallbackVerifier(WechatPaySdkClient client) {
        this.client = client;
    }

    public PaymentCallbackRequest verify(String body,
                                         String serial,
                                         String timestamp,
                                         String nonce,
                                         String signature) {
        WechatPayNotification notification = client.verifyNotification(
                body, serial, timestamp, nonce, signature);
        return new PaymentCallbackRequest(
                notification.paymentId(), notification.notifyId(), notification.transactionId(),
                notification.amount(), notification.status(), "wechatpay-v3-verified");
    }
}

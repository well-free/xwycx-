package org.example.web;

import org.example.payment.PaymentChannel;
import org.example.service.PaymentService;
import org.example.wechat.WechatPayCallbackVerifier;
import org.example.web.dto.PaymentCallbackRequest;
import org.example.wechat.WechatPayRefundNotification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@ConditionalOnProperty(name = "app.payment.mode", havingValue = "gateway")
public class WechatPayCallbackController {
    private final WechatPayCallbackVerifier verifier;
    private final PaymentService paymentService;

    public WechatPayCallbackController(WechatPayCallbackVerifier verifier, PaymentService paymentService) {
        this.verifier = verifier;
        this.paymentService = paymentService;
    }

    @PostMapping("/api/payments/callbacks/wechat")
    public Map<String, String> callback(
            @RequestBody String body,
            @RequestHeader("Wechatpay-Serial") String serial,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Signature") String signature) {
        PaymentCallbackRequest command = verifier.verify(body, serial, timestamp, nonce, signature);
        paymentService.handleVerifiedCallback(PaymentChannel.WECHAT, command);
        return Map.of("code", "SUCCESS", "message", "success");
    }

    @PostMapping("/api/payments/callbacks/wechat/refund")
    public Map<String, String> refundCallback(
            @RequestBody String body,
            @RequestHeader("Wechatpay-Serial") String serial,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Signature") String signature) {
        WechatPayRefundNotification notification = verifier.verifyRefund(
                body, serial, timestamp, nonce, signature);
        paymentService.handleVerifiedRefund(
                notification.refundId(), notification.channelRefundNo(), notification.status());
        return Map.of("code", "SUCCESS", "message", "success");
    }
}

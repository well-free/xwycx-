package org.example.service;

import org.example.payment.PaymentChannel;
import org.example.payment.PaymentStatus;
import org.example.trade.OrderSide;
import org.example.web.dto.OrderCreateRequest;
import org.example.web.dto.PaymentCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false",
        "app.payment.mode=sandbox",
        "app.payment.callback-secret=sandbox-secret",
        "app.payment.alipay-app-id=2021000123456789"
})
@Transactional
class PaymentSandboxServiceTest {
    @Autowired
    private OrderCommandService orderCommandService;

    @Autowired
    private PaymentService paymentService;

    @Test
    void shouldCreateAlipaySandboxPaymentFromService() {
        var order = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, new BigDecimal("9.90"), 2));

        var payment = paymentService.create(new PaymentCreateRequest(order.order().id(), PaymentChannel.ALIPAY));

        assertThat(payment.gatewayMode()).isEqualTo("sandbox");
        assertThat(payment.status()).isEqualTo(PaymentStatus.PAYING);
        assertThat(payment.payUrl()).contains("openapi-sandbox.dl.alipaydev.com/gateway.do");
        assertThat(payment.payUrl()).contains("app_id=2021000123456789");
        assertThat(payment.qrCode()).isEqualTo("ALIPAY_SANDBOX:" + payment.id() + ":19.80");
    }
}

package org.example.service;

import org.example.infrastructure.mybatis.mapper.PaymentCallbackMapper;
import org.example.payment.PaymentChannel;
import org.example.payment.PaymentStatus;
import org.example.refund.RefundStatus;
import org.example.trade.OrderSide;
import org.example.web.BusinessException;
import org.example.web.dto.OrderCreateRequest;
import org.example.web.dto.PaymentCallbackRequest;
import org.example.web.dto.PaymentCreateRequest;
import org.example.web.dto.RefundCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false"
})
@Transactional
class PaymentServiceTest {
    @Autowired
    private OrderCommandService orderCommandService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentCallbackMapper paymentCallbackMapper;

    @Test
    void shouldCreatePaymentForOrder() {
        var order = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, new BigDecimal("12.50"), 2));

        var payment = paymentService.create(new PaymentCreateRequest(order.order().id(), PaymentChannel.ALIPAY));

        assertThat(payment.orderId()).isEqualTo(order.order().id());
        assertThat(payment.channel()).isEqualTo(PaymentChannel.ALIPAY);
        assertThat(payment.amount()).isEqualByComparingTo("25.00");
        assertThat(payment.status()).isEqualTo(PaymentStatus.PAYING);
        assertThat(payment.payUrl()).contains("/pay/alipay");
        assertThat(payment.qrCode()).contains(String.valueOf(payment.id()));
    }

    @Test
    void shouldReuseOpenPaymentForSameOrderAndChannel() {
        var order = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, new BigDecimal("8.00"), 3));

        var first = paymentService.create(new PaymentCreateRequest(order.order().id(), PaymentChannel.WECHAT));
        var second = paymentService.create(new PaymentCreateRequest(order.order().id(), PaymentChannel.WECHAT));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo(PaymentStatus.PAYING);
    }

    @Test
    void shouldMarkPaymentSuccessIdempotentlyFromCallback() {
        var order = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, new BigDecimal("9.90"), 2));
        var payment = paymentService.create(new PaymentCreateRequest(order.order().id(), PaymentChannel.ALIPAY));
        var request = new PaymentCallbackRequest(payment.id(), "notify-1", "trade-1", new BigDecimal("19.80"), "SUCCESS", "mock-signature");

        var first = paymentService.handleCallback(PaymentChannel.ALIPAY, request);
        var second = paymentService.handleCallback(PaymentChannel.ALIPAY, request);

        assertThat(first.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(second.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(paymentCallbackMapper.selectList(null)).hasSize(1);
    }

    @Test
    void shouldRejectCallbackWithWrongAmount() {
        var order = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, new BigDecimal("10.00"), 2));
        var payment = paymentService.create(new PaymentCreateRequest(order.order().id(), PaymentChannel.ALIPAY));
        var request = new PaymentCallbackRequest(payment.id(), "notify-2", "trade-2", new BigDecimal("19.99"), "SUCCESS", "mock-signature");

        assertThatThrownBy(() -> paymentService.handleCallback(PaymentChannel.ALIPAY, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("amount mismatch");
    }

    @Test
    void shouldRefundSuccessfulPayment() {
        var order = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, new BigDecimal("10.00"), 1));
        var payment = paymentService.create(new PaymentCreateRequest(order.order().id(), PaymentChannel.WECHAT));
        paymentService.handleCallback(PaymentChannel.WECHAT,
                new PaymentCallbackRequest(payment.id(), "notify-3", "trade-3", new BigDecimal("10.00"), "SUCCESS", "mock-signature"));

        var refund = paymentService.refund(payment.id(), new RefundCreateRequest(new BigDecimal("10.00"), "user requested refund"));

        assertThat(refund.paymentId()).isEqualTo(payment.id());
        assertThat(refund.status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(paymentService.get(payment.id()).status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void shouldExposePaymentGatewayMode() {
        var order = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, new BigDecimal("10.00"), 1));

        var payment = paymentService.create(new PaymentCreateRequest(order.order().id(), PaymentChannel.ALIPAY));

        assertThat(payment.gatewayMode()).isEqualTo("mock");
    }
}

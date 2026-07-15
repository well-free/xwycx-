package org.example.service;

import org.example.infrastructure.mybatis.mapper.PaymentCallbackMapper;
import org.example.payment.PaymentChannel;
import org.example.payment.PaymentGateway;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:payment_service_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
})
class PaymentServiceTest {
    @Autowired
    private OrderCommandService orderCommandService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentCallbackMapper paymentCallbackMapper;

    @MockitoSpyBean
    private PaymentGateway paymentGateway;

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
    void shouldPersistAndReturnWechatMiniProgramPaymentParameters() {
        var order = orderCommandService.place(new OrderCreateRequest(
                "MASK-50", OrderSide.BUY, new BigDecimal("8.00"), 3));

        var payment = paymentService.create(new PaymentCreateRequest(order.order().id(), PaymentChannel.WECHAT));
        var loaded = paymentService.get(payment.id());

        assertThat(payment.prepayId()).isEqualTo("mock-" + payment.id());
        assertThat(payment.miniProgram().packageValue()).isEqualTo("prepay_id=mock-" + payment.id());
        assertThat(loaded.miniProgram()).isEqualTo(payment.miniProgram());
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
        assertThat(paymentCallbackMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<org.example.infrastructure.mybatis.entity.PaymentCallbackEntity>()
                        .eq(org.example.infrastructure.mybatis.entity.PaymentCallbackEntity::getNotifyId, "notify-1")))
                .isEqualTo(1L);
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
        verify(paymentGateway).refund(eq(PaymentChannel.WECHAT), anyLong(), eq(payment.id()),
                eq(new BigDecimal("10.00")), eq(new BigDecimal("10.00")), eq("trade-3"));
    }

    @Test
    void shouldExposePaymentGatewayMode() {
        var order = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, new BigDecimal("10.00"), 1));

        var payment = paymentService.create(new PaymentCreateRequest(order.order().id(), PaymentChannel.ALIPAY));

        assertThat(payment.gatewayMode()).isEqualTo("mock");
    }
}

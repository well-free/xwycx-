package org.example.payment;

import org.example.config.AppProperties;
import org.example.refund.RefundStatus;
import org.example.web.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredPaymentGatewayTest {
    @Test
    void shouldCreateWechatMiniProgramParametersInMockMode() {
        AppProperties properties = new AppProperties();
        ConfiguredPaymentGateway gateway = new ConfiguredPaymentGateway(properties);

        PaymentGatewayResult result = gateway.createPayment(
                PaymentChannel.WECHAT, 88L, new BigDecimal("12.80"));

        assertThat(result.prepayId()).isEqualTo("mock-88");
        assertThat(result.miniProgram()).isNotNull();
        assertThat(result.miniProgram().packageValue()).isEqualTo("prepay_id=mock-88");
        assertThat(result.miniProgram().signType()).isEqualTo("RSA");
        assertThat(result.miniProgram().paySign()).isEqualTo("MOCK-PAY-SIGN-88");
    }

    @Test
    void shouldCreateAlipaySandboxPaymentPayload() {
        AppProperties properties = new AppProperties();
        properties.getPayment().setMode("sandbox");
        properties.getPayment().setCallbackBaseUrl("https://xwycx.xyz");
        properties.getPayment().setCallbackSecret("sandbox-secret");
        properties.getPayment().setAlipayAppId("2021000123456789");
        ConfiguredPaymentGateway gateway = new ConfiguredPaymentGateway(properties);

        PaymentGatewayResult result = gateway.createPayment(PaymentChannel.ALIPAY, 1001L, new BigDecimal("19.80"));

        assertThat(result.payUrl()).startsWith("https://openapi-sandbox.dl.alipaydev.com/gateway.do?");
        assertThat(result.payUrl()).contains("app_id=2021000123456789");
        assertThat(result.payUrl()).contains("method=alipay.trade.page.pay");
        assertThat(result.payUrl()).contains("notify_url=https%3A%2F%2Fxwycx.xyz%2Fapi%2Fpayments%2Fcallbacks%2Falipay");
        assertThat(result.payUrl()).contains("out_trade_no%22%3A%221001%22");
        assertThat(result.payUrl()).contains("total_amount%22%3A%2219.80%22");
        assertThat(result.qrCode()).isEqualTo("ALIPAY_SANDBOX:1001:19.80");
        assertThat(gateway.verifyCallback(PaymentChannel.ALIPAY, "sandbox-secret")).isTrue();
        assertThat(gateway.verifyCallback(PaymentChannel.ALIPAY, "bad")).isFalse();
        assertThat(gateway.createRefundNo(2002L)).isEqualTo("ALIPAY-SANDBOX-REFUND-2002");
        PaymentRefundResult refund = gateway.refund(
                PaymentChannel.ALIPAY, 2002L, 1001L,
                new BigDecimal("19.80"), new BigDecimal("19.80"), "trade-1001");
        assertThat(refund.channelRefundNo()).isEqualTo("ALIPAY-SANDBOX-REFUND-2002");
        assertThat(refund.status()).isEqualTo(RefundStatus.SUCCESS);
    }

    @Test
    void shouldRejectAlipaySandboxWhenAppIdMissing() {
        AppProperties properties = new AppProperties();
        properties.getPayment().setMode("sandbox");
        properties.getPayment().setCallbackSecret("sandbox-secret");
        ConfiguredPaymentGateway gateway = new ConfiguredPaymentGateway(properties);

        assertThatThrownBy(() -> gateway.createPayment(PaymentChannel.ALIPAY, 1001L, new BigDecimal("19.80")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("alipay app id is not configured");
    }

    @Test
    void gatewayRefundPlaceholderIsRetryable() {
        AppProperties properties = new AppProperties();
        properties.getPayment().setMode("gateway");
        ConfiguredPaymentGateway gateway = new ConfiguredPaymentGateway(properties);

        assertThatThrownBy(() -> gateway.refund(
                PaymentChannel.ALIPAY, 2002L, 1001L,
                new BigDecimal("19.80"), new BigDecimal("19.80"), "trade-1001"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getStatus().value()).isEqualTo(503));
    }
}

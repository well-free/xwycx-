package org.example.wechat;

import org.example.config.AppProperties;
import org.example.payment.PaymentChannel;
import org.example.payment.PaymentGatewayRequest;
import org.example.payment.PaymentRefundResult;
import org.example.refund.RefundStatus;
import org.example.web.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WechatPayGatewayTest {
    private final AppProperties properties = configuredProperties();
    private final WechatPaySdkClient client = mock(WechatPaySdkClient.class);
    private final WechatPayGateway gateway = new WechatPayGateway(properties, client);

    @Test
    void shouldMapJsapiPrepayResponseToRequestPaymentFields() {
        when(client.prepay(9L, new BigDecimal("12.80"), "openid-9"))
                .thenReturn(new WechatPayPrepayResult(
                        "wx-prepay-1", "1710000000", "nonce-1",
                        "prepay_id=wx-prepay-1", "RSA", "pay-sign-1"));

        var result = gateway.createPayment(new PaymentGatewayRequest(
                PaymentChannel.WECHAT, 9L, new BigDecimal("12.80"), "openid-9"));

        assertThat(result.prepayId()).isEqualTo("wx-prepay-1");
        assertThat(result.miniProgram().packageValue()).isEqualTo("prepay_id=wx-prepay-1");
        assertThat(result.miniProgram().paySign()).isEqualTo("pay-sign-1");
    }

    @Test
    void shouldRequireBoundWechatIdentity() {
        assertThatThrownBy(() -> gateway.createPayment(new PaymentGatewayRequest(
                PaymentChannel.WECHAT, 9L, new BigDecimal("12.80"), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("wechat account binding required");
    }

    @Test
    void shouldDelegateRefundToOfficialClient() {
        when(client.refund(21L, 9L, new BigDecimal("2.80"), new BigDecimal("12.80"), "wx-trade-9"))
                .thenReturn(new PaymentRefundResult("wx-refund-21", RefundStatus.PROCESSING));

        var result = gateway.refund(
                21L, 9L, new BigDecimal("2.80"), new BigDecimal("12.80"), "wx-trade-9");

        assertThat(result.status()).isEqualTo(RefundStatus.PROCESSING);
        verify(client).refund(
                21L, 9L, new BigDecimal("2.80"), new BigDecimal("12.80"), "wx-trade-9");
    }

    private static AppProperties configuredProperties() {
        AppProperties properties = new AppProperties();
        properties.getPayment().setMode("gateway");
        properties.getPayment().setWechatMchId("mch-1");
        properties.getWechat().setAppId("app-1");
        return properties;
    }
}

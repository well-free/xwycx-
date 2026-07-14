package org.example.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationTest {
    private final ProductionConfigurationValidator validator = new ProductionConfigurationValidator();

    @Test
    void gatewayModeRejectsMissingWechatSecrets() {
        AppProperties properties = new AppProperties();
        properties.getPayment().setMode("gateway");

        assertThatThrownBy(() -> validator.validateWechatPayment(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wechat app id");
    }

    @Test
    void gatewayModeRequiresHttpsCallbacksAndThirtyTwoCharacterApiV3Key() {
        AppProperties invalidCallback = configured();
        invalidCallback.getPayment().setWechatNotifyUrl("http://xwycx.xyz/callback");
        assertThatThrownBy(() -> validator.validateWechatPayment(invalidCallback)).hasMessageContaining("must use https");

        AppProperties invalidKey = configured();
        invalidKey.getPayment().setWechatApiV3Key("too-short");
        assertThatThrownBy(() -> validator.validateWechatPayment(invalidKey)).hasMessageContaining("32 characters");
    }

    private AppProperties configured() {
        AppProperties properties = new AppProperties();
        properties.getPayment().setMode("gateway");
        properties.getWechat().setAppId("wx-app-id");
        properties.getPayment().setWechatMchId("merchant-id");
        properties.getPayment().setWechatMerchantSerialNumber("serial-number");
        properties.getPayment().setWechatPrivateKeyPath("/etc/xwycx/apiclient_key.pem");
        properties.getPayment().setWechatApiV3Key("12345678901234567890123456789012");
        properties.getPayment().setWechatNotifyUrl("https://xwycx.xyz/api/payments/callbacks/wechat");
        properties.getPayment().setWechatRefundNotifyUrl("https://xwycx.xyz/api/payments/callbacks/wechat/refund");
        return properties;
    }
}

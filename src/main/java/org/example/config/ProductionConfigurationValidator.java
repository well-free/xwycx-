package org.example.config;

import org.springframework.stereotype.Component;

@Component
public class ProductionConfigurationValidator {
    public void validateWechatPayment(AppProperties properties) {
        if (!"gateway".equalsIgnoreCase(properties.getPayment().getMode())) {
            return;
        }
        require(properties.getWechat().getAppId(), "wechat app id");
        require(properties.getPayment().getWechatMchId(), "wechat merchant id");
        require(properties.getPayment().getWechatMerchantSerialNumber(), "wechat merchant serial number");
        require(properties.getPayment().getWechatPrivateKeyPath(), "wechat private key path");
        require(properties.getPayment().getWechatApiV3Key(), "wechat api v3 key");
        requireHttps(properties.getPayment().getWechatNotifyUrl(), "wechat notify url");
        requireHttps(properties.getPayment().getWechatRefundNotifyUrl(), "wechat refund notify url");
        if (properties.getPayment().getWechatApiV3Key().length() != 32) {
            throw new IllegalStateException("wechat api v3 key must be 32 characters");
        }
    }

    private void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not configured");
        }
    }

    private void requireHttps(String value, String name) {
        require(value, name);
        if (!value.startsWith("https://")) {
            throw new IllegalStateException(name + " must use https");
        }
    }
}

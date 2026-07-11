package org.example.payment;

import org.example.config.AppProperties;
import org.example.web.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

@Component
public class ConfiguredPaymentGateway implements PaymentGateway {
    private final AppProperties properties;

    public ConfiguredPaymentGateway(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public PaymentGatewayResult createPayment(PaymentChannel channel, long paymentId, BigDecimal amount) {
        if (!"mock".equalsIgnoreCase(properties.getPayment().getMode())) {
            requireConfigured(channel);
        }
        String channelName = channel.name().toLowerCase(Locale.ROOT);
        String base = properties.getPayment().getCallbackBaseUrl();
        String payUrl = base + "/pay/" + channelName + "?paymentId=" + paymentId;
        String qrCode = "PAY:" + channel.name() + ":" + paymentId + ":" + amount;
        return new PaymentGatewayResult(payUrl, qrCode);
    }

    @Override
    public boolean verifyCallback(PaymentChannel channel, String signature) {
        if (!"mock".equalsIgnoreCase(properties.getPayment().getMode())) {
            requireConfigured(channel);
        }
        return properties.getPayment().getCallbackSecret().equals(signature);
    }

    @Override
    public String createRefundNo(long refundId) {
        return ("mock".equalsIgnoreCase(properties.getPayment().getMode()) ? "MOCK" : "GW") + "-REFUND-" + refundId;
    }

    private void requireConfigured(PaymentChannel channel) {
        if (properties.getPayment().getCallbackSecret() == null || properties.getPayment().getCallbackSecret().isBlank()) {
            throw BusinessException.conflict("payment callback secret is not configured");
        }
        if (channel == PaymentChannel.ALIPAY && properties.getPayment().getAlipayAppId().isBlank()) {
            throw BusinessException.conflict("alipay app id is not configured");
        }
        if (channel == PaymentChannel.WECHAT && properties.getPayment().getWechatMchId().isBlank()) {
            throw BusinessException.conflict("wechat merchant id is not configured");
        }
    }
}

package org.example.payment;

import org.example.config.AppProperties;
import org.example.web.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
public class ConfiguredPaymentGateway implements PaymentGateway {
    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppProperties properties;

    public ConfiguredPaymentGateway(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public PaymentGatewayResult createPayment(PaymentChannel channel, long paymentId, BigDecimal amount) {
        if (isSandbox()) {
            return createSandboxPayment(channel, paymentId, amount);
        }
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
        if (isSandbox()) {
            requireConfigured(channel);
            return properties.getPayment().getCallbackSecret().equals(signature);
        }
        if (!isMock()) {
            requireConfigured(channel);
        }
        return properties.getPayment().getCallbackSecret().equals(signature);
    }

    @Override
    public String createRefundNo(long refundId) {
        if (isSandbox()) {
            return "ALIPAY-SANDBOX-REFUND-" + refundId;
        }
        return (isMock() ? "MOCK" : "GW") + "-REFUND-" + refundId;
    }

    private PaymentGatewayResult createSandboxPayment(PaymentChannel channel, long paymentId, BigDecimal amount) {
        if (channel != PaymentChannel.ALIPAY) {
            throw BusinessException.conflict("sandbox mode currently supports alipay only");
        }
        requireConfigured(channel);
        String normalizedAmount = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        String bizContent = "{\"out_trade_no\":\"" + paymentId + "\",\"total_amount\":\"" + normalizedAmount
                + "\",\"subject\":\"xwycx disposable supplies order\",\"product_code\":\"FAST_INSTANT_TRADE_PAY\"}";
        String payUrl = properties.getPayment().getAlipaySandboxGatewayUrl()
                + "?app_id=" + encode(properties.getPayment().getAlipayAppId())
                + "&method=alipay.trade.page.pay"
                + "&charset=utf-8"
                + "&sign_type=RSA2"
                + "&timestamp=" + encode(LocalDateTime.now().format(ALIPAY_TIME))
                + "&version=1.0"
                + "&notify_url=" + encode(properties.getPayment().getCallbackBaseUrl() + "/api/payments/callbacks/alipay")
                + "&return_url=" + encode(properties.getPayment().getCallbackBaseUrl() + "/orders.html")
                + "&biz_content=" + encode(bizContent);
        return new PaymentGatewayResult(payUrl, "ALIPAY_SANDBOX:" + paymentId + ":" + normalizedAmount);
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

    private boolean isMock() {
        return "mock".equalsIgnoreCase(properties.getPayment().getMode());
    }

    private boolean isSandbox() {
        return "sandbox".equalsIgnoreCase(properties.getPayment().getMode());
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

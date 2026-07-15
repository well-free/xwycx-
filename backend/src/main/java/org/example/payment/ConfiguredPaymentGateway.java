package org.example.payment;

import org.example.config.AppProperties;
import org.example.refund.RefundStatus;
import org.example.web.BusinessException;
import org.example.wechat.WechatPayGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
public class ConfiguredPaymentGateway implements PaymentGateway {
    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppProperties properties;
    private final WechatPayGateway wechatPayGateway;

    public ConfiguredPaymentGateway(AppProperties properties) {
        this(properties, null);
    }

    @Autowired
    public ConfiguredPaymentGateway(AppProperties properties,
                                    ObjectProvider<WechatPayGateway> wechatPayGatewayProvider) {
        this.properties = properties;
        this.wechatPayGateway = wechatPayGatewayProvider == null ? null : wechatPayGatewayProvider.getIfAvailable();
    }

    @Override
    public PaymentGatewayResult createPayment(PaymentGatewayRequest request) {
        if (isGateway()) {
            if (request.channel() != PaymentChannel.WECHAT) {
                throw BusinessException.serviceUnavailable("alipay production gateway is not configured");
            }
            return requireWechatGateway().createPayment(request);
        }
        return createPayment(request.channel(), request.paymentId(), request.amount());
    }

    @Override
    public PaymentGatewayResult createPayment(PaymentChannel channel, long paymentId, BigDecimal amount) {
        if (isSandbox()) {
            return createSandboxPayment(channel, paymentId, amount);
        }
        if (isMock() && channel == PaymentChannel.WECHAT) {
            String prepayId = "mock-" + paymentId;
            MiniProgramPaymentParameters parameters = new MiniProgramPaymentParameters(
                    String.valueOf(Instant.now().getEpochSecond()),
                    "mock-nonce-" + paymentId,
                    "prepay_id=" + prepayId,
                    "RSA",
                    "MOCK-PAY-SIGN-" + paymentId);
            return new PaymentGatewayResult(null, null, prepayId, parameters);
        }
        if (isGateway()) {
            return createPayment(new PaymentGatewayRequest(channel, paymentId, amount, null));
        }
        String channelName = channel.name().toLowerCase(Locale.ROOT);
        String base = properties.getPayment().getCallbackBaseUrl();
        String payUrl = base + "/pay/" + channelName + "?paymentId=" + paymentId;
        String qrCode = "PAY:" + channel.name() + ":" + paymentId + ":" + amount;
        return new PaymentGatewayResult(payUrl, qrCode, null, null);
    }

    @Override
    public boolean verifyCallback(PaymentChannel channel, String signature) {
        if (isSandbox()) {
            requireConfigured(channel);
            return properties.getPayment().getCallbackSecret().equals(signature);
        }
        if (isGateway() && channel == PaymentChannel.WECHAT) {
            return false;
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

    @Override
    public PaymentRefundResult refund(PaymentChannel channel,
                                      long refundId,
                                      long paymentId,
                                      BigDecimal refundAmount,
                                      BigDecimal totalAmount,
                                      String channelTradeNo) {
        if (!isMock() && !isSandbox()) {
            if (channel != PaymentChannel.WECHAT) {
                throw BusinessException.serviceUnavailable("alipay production gateway is not configured");
            }
            return requireWechatGateway().refund(
                    refundId, paymentId, refundAmount, totalAmount, channelTradeNo);
        }
        if (isSandbox()) {
            requireConfigured(channel);
        }
        return new PaymentRefundResult(createRefundNo(refundId), RefundStatus.SUCCESS);
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
        return new PaymentGatewayResult(payUrl, "ALIPAY_SANDBOX:" + paymentId + ":" + normalizedAmount,
                null, null);
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

    private boolean isGateway() {
        return "gateway".equalsIgnoreCase(properties.getPayment().getMode());
    }

    private WechatPayGateway requireWechatGateway() {
        if (wechatPayGateway == null) {
            throw BusinessException.serviceUnavailable("wechat pay gateway is not available");
        }
        return wechatPayGateway;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

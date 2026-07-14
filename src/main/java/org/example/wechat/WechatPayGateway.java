package org.example.wechat;

import org.example.config.AppProperties;
import org.example.payment.MiniProgramPaymentParameters;
import org.example.payment.PaymentChannel;
import org.example.payment.PaymentGatewayRequest;
import org.example.payment.PaymentGatewayResult;
import org.example.payment.PaymentRefundResult;
import org.example.web.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConditionalOnProperty(name = "app.payment.mode", havingValue = "gateway")
public class WechatPayGateway {
    private final AppProperties properties;
    private final WechatPaySdkClient client;

    public WechatPayGateway(AppProperties properties, WechatPaySdkClient client) {
        this.properties = properties;
        this.client = client;
    }

    public PaymentGatewayResult createPayment(PaymentGatewayRequest request) {
        if (request.channel() != PaymentChannel.WECHAT) {
            throw BusinessException.badRequest("wechat pay channel required");
        }
        if (request.payerOpenId() == null || request.payerOpenId().isBlank()) {
            throw BusinessException.conflict("wechat account binding required");
        }
        if (properties.getWechat().getAppId().isBlank()
                || properties.getPayment().getWechatMchId().isBlank()) {
            throw BusinessException.serviceUnavailable("wechat pay merchant configuration is missing");
        }
        WechatPayPrepayResult result = client.prepay(
                request.paymentId(), request.amount(), request.payerOpenId());
        MiniProgramPaymentParameters parameters = new MiniProgramPaymentParameters(
                result.timeStamp(), result.nonceStr(), result.packageValue(), result.signType(), result.paySign());
        return new PaymentGatewayResult(null, null, result.prepayId(), parameters);
    }

    public PaymentRefundResult refund(long refundId,
                                      long paymentId,
                                      BigDecimal refundAmount,
                                      BigDecimal totalAmount,
                                      String channelTradeNo) {
        return client.refund(refundId, paymentId, refundAmount, totalAmount, channelTradeNo);
    }
}

package org.example.wechat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.Amount;
import com.wechat.pay.java.service.payments.jsapi.model.Payer;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Status;
import org.example.config.AppProperties;
import org.example.config.ProductionConfigurationValidator;
import org.example.payment.PaymentRefundResult;
import org.example.refund.RefundStatus;
import org.example.web.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@ConditionalOnProperty(name = "app.payment.mode", havingValue = "gateway")
public class OfficialWechatPaySdkClient implements WechatPaySdkClient {
    private static final DateTimeFormatter WECHAT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final JsapiServiceExtension jsapiService;
    private final RefundService refundService;
    private final NotificationParser notificationParser;

    public OfficialWechatPaySdkClient(AppProperties properties,
                                      ObjectMapper objectMapper,
                                      ProductionConfigurationValidator validator) {
        validator.validateWechatPayment(properties);
        this.properties = properties;
        this.objectMapper = objectMapper;
        RSAAutoCertificateConfig config = new RSAAutoCertificateConfig.Builder()
                .merchantId(properties.getPayment().getWechatMchId())
                .privateKeyFromPath(properties.getPayment().getWechatPrivateKeyPath())
                .merchantSerialNumber(properties.getPayment().getWechatMerchantSerialNumber())
                .apiV3Key(properties.getPayment().getWechatApiV3Key())
                .build();
        this.jsapiService = new JsapiServiceExtension.Builder().config(config).build();
        this.refundService = new RefundService.Builder().config(config).build();
        this.notificationParser = new NotificationParser(config);
    }

    @Override
    public WechatPayPrepayResult prepay(long paymentId, BigDecimal amount, String payerOpenId) {
        PrepayRequest request = new PrepayRequest();
        request.setAppid(properties.getWechat().getAppId());
        request.setMchid(properties.getPayment().getWechatMchId());
        request.setDescription("xwycx disposable supplies order");
        request.setOutTradeNo(String.valueOf(paymentId));
        request.setTimeExpire(OffsetDateTime.now(ZoneOffset.ofHours(8))
                .plusSeconds(properties.getPayment().getTimeoutSeconds())
                .format(WECHAT_TIME));
        request.setNotifyUrl(properties.getPayment().getWechatNotifyUrl());
        Amount requestAmount = new Amount();
        requestAmount.setTotal(toFen(amount));
        requestAmount.setCurrency("CNY");
        request.setAmount(requestAmount);
        Payer payer = new Payer();
        payer.setOpenid(payerOpenId);
        request.setPayer(payer);
        try {
            PrepayWithRequestPaymentResponse response = jsapiService.prepayWithRequestPayment(request);
            String packageValue = response.getPackageVal();
            return new WechatPayPrepayResult(
                    extractPrepayId(packageValue), response.getTimeStamp(), response.getNonceStr(),
                    packageValue, response.getSignType(), response.getPaySign());
        } catch (RuntimeException exception) {
            throw BusinessException.serviceUnavailable("wechat pay prepay failed");
        }
    }

    @Override
    public PaymentRefundResult refund(long refundId,
                                      long paymentId,
                                      BigDecimal refundAmount,
                                      BigDecimal totalAmount,
                                      String channelTradeNo) {
        CreateRequest request = new CreateRequest();
        request.setOutTradeNo(String.valueOf(paymentId));
        request.setOutRefundNo(String.valueOf(refundId));
        request.setReason("customer refund");
        request.setNotifyUrl(properties.getPayment().getWechatRefundNotifyUrl());
        AmountReq amount = new AmountReq();
        amount.setRefund((long) toFen(refundAmount));
        amount.setTotal((long) toFen(totalAmount));
        amount.setCurrency("CNY");
        request.setAmount(amount);
        try {
            Refund response = refundService.create(request);
            return new PaymentRefundResult(response.getRefundId(), mapRefundStatus(response.getStatus()));
        } catch (RuntimeException exception) {
            throw BusinessException.serviceUnavailable("wechat pay refund failed");
        }
    }

    @Override
    public WechatPayNotification verifyNotification(String body,
                                                     String serial,
                                                     String timestamp,
                                                     String nonce,
                                                     String signature) {
        RequestParam requestParam = new RequestParam.Builder()
                .serialNumber(serial)
                .timestamp(timestamp)
                .nonce(nonce)
                .signature(signature)
                .body(body)
                .build();
        try {
            Transaction transaction = notificationParser.parse(requestParam, Transaction.class);
            validateMerchant(transaction);
            long paymentId = Long.parseLong(transaction.getOutTradeNo());
            String notifyId = objectMapper.readTree(body).path("id").asText();
            if (notifyId.isBlank() || transaction.getAmount() == null || transaction.getAmount().getTotal() == null) {
                throw BusinessException.badRequest("invalid wechat pay callback payload");
            }
            String status = transaction.getTradeState() == Transaction.TradeStateEnum.SUCCESS
                    ? "SUCCESS" : "FAILED";
            return new WechatPayNotification(
                    paymentId, notifyId, transaction.getTransactionId(),
                    BigDecimal.valueOf(transaction.getAmount().getTotal(), 2), status);
        } catch (BusinessException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw BusinessException.badRequest("invalid wechat pay callback");
        }
    }

    @Override
    public WechatPayRefundNotification verifyRefundNotification(String body,
                                                                 String serial,
                                                                 String timestamp,
                                                                 String nonce,
                                                                 String signature) {
        RequestParam requestParam = new RequestParam.Builder()
                .serialNumber(serial)
                .timestamp(timestamp)
                .nonce(nonce)
                .signature(signature)
                .body(body)
                .build();
        try {
            RefundNotification notification = notificationParser.parse(requestParam, RefundNotification.class);
            return new WechatPayRefundNotification(
                    Long.parseLong(notification.getOutRefundNo()),
                    notification.getRefundId(),
                    mapRefundStatus(notification.getRefundStatus()));
        } catch (RuntimeException exception) {
            throw BusinessException.badRequest("invalid wechat refund callback");
        }
    }

    private void validateMerchant(Transaction transaction) {
        if (!properties.getWechat().getAppId().equals(transaction.getAppid())
                || !properties.getPayment().getWechatMchId().equals(transaction.getMchid())) {
            throw BusinessException.badRequest("wechat pay merchant mismatch");
        }
    }

    private static int toFen(BigDecimal amount) {
        try {
            return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException exception) {
            throw BusinessException.badRequest("payment amount has invalid precision");
        }
    }

    private static String extractPrepayId(String packageValue) {
        String prefix = "prepay_id=";
        if (packageValue == null || !packageValue.startsWith(prefix)) {
            throw BusinessException.serviceUnavailable("wechat pay prepay response is invalid");
        }
        return packageValue.substring(prefix.length());
    }

    private static RefundStatus mapRefundStatus(Status status) {
        if (status == Status.SUCCESS) {
            return RefundStatus.SUCCESS;
        }
        if (status == Status.PROCESSING) {
            return RefundStatus.PROCESSING;
        }
        return RefundStatus.FAILED;
    }

}

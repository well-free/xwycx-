package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.example.config.AppProperties;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mybatis.entity.OrderEntity;
import org.example.infrastructure.mybatis.entity.PaymentCallbackEntity;
import org.example.infrastructure.mybatis.entity.PaymentOrderEntity;
import org.example.infrastructure.mybatis.entity.RefundOrderEntity;
import org.example.infrastructure.mybatis.mapper.OrderMapper;
import org.example.infrastructure.mybatis.mapper.PaymentCallbackMapper;
import org.example.infrastructure.mybatis.mapper.PaymentOrderMapper;
import org.example.infrastructure.mybatis.mapper.RefundOrderMapper;
import org.example.payment.PaymentChannel;
import org.example.payment.PaymentStatus;
import org.example.refund.RefundStatus;
import org.example.trade.OrderStatus;
import org.example.web.BusinessException;
import org.example.web.dto.PaymentCallbackRequest;
import org.example.web.dto.PaymentCreateRequest;
import org.example.web.dto.PaymentResponse;
import org.example.web.dto.RefundCreateRequest;
import org.example.web.dto.RefundResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class PaymentService {
    private static final String MOCK_SIGNATURE = "mock-signature";
    private static final Set<String> OPEN_PAYMENT_STATUSES = Set.of(PaymentStatus.PENDING.name(), PaymentStatus.PAYING.name());

    private final PaymentOrderMapper paymentMapper;
    private final PaymentCallbackMapper callbackMapper;
    private final RefundOrderMapper refundMapper;
    private final OrderMapper orderMapper;
    private final DistributedLockService lockService;
    private final AppProperties properties;

    public PaymentService(PaymentOrderMapper paymentMapper,
                          PaymentCallbackMapper callbackMapper,
                          RefundOrderMapper refundMapper,
                          OrderMapper orderMapper,
                          DistributedLockService lockService,
                          AppProperties properties) {
        this.paymentMapper = paymentMapper;
        this.callbackMapper = callbackMapper;
        this.refundMapper = refundMapper;
        this.orderMapper = orderMapper;
        this.lockService = lockService;
        this.properties = properties;
    }

    public PaymentResponse create(PaymentCreateRequest request) {
        return lockService.withLock("payment:order:" + request.orderId() + ":" + request.channel(), () -> {
            OrderEntity order = requirePayableOrder(request.orderId());
            PaymentOrderEntity existing = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrderEntity>()
                    .eq(PaymentOrderEntity::getOrderId, request.orderId())
                    .eq(PaymentOrderEntity::getChannel, request.channel().name()));
            if (existing != null) {
                if (OPEN_PAYMENT_STATUSES.contains(existing.getStatus())
                        || PaymentStatus.SUCCESS.name().equals(existing.getStatus())
                        || PaymentStatus.REFUNDING.name().equals(existing.getStatus())
                        || PaymentStatus.REFUNDED.name().equals(existing.getStatus())) {
                    return toResponse(existing);
                }
                Instant now = Instant.now();
                existing.setAmount(amountOf(order));
                existing.setStatus(PaymentStatus.PAYING.name());
                existing.setChannelTradeNo(null);
                existing.setPaidAt(null);
                existing.setClosedAt(null);
                existing.setUpdatedAt(now);
                existing.setExpireAt(now.plusSeconds(properties.getPayment().getTimeoutSeconds()));
                existing.setPayUrl(mockPayUrl(request.channel(), existing.getId()));
                existing.setQrCode("PAY:" + request.channel().name() + ":" + existing.getId() + ":" + existing.getAmount());
                updatePayment(existing);
                return toResponse(existing);
            }

            Instant now = Instant.now();
            PaymentOrderEntity payment = new PaymentOrderEntity();
            payment.setId(IdWorker.getId());
            payment.setOrderId(order.getId());
            payment.setChannel(request.channel().name());
            payment.setAmount(amountOf(order));
            payment.setStatus(PaymentStatus.PAYING.name());
            payment.setVersion(0L);
            payment.setCreatedAt(now);
            payment.setUpdatedAt(now);
            payment.setExpireAt(now.plusSeconds(properties.getPayment().getTimeoutSeconds()));
            payment.setPayUrl(mockPayUrl(request.channel(), payment.getId()));
            payment.setQrCode("PAY:" + request.channel().name() + ":" + payment.getId() + ":" + payment.getAmount());
            paymentMapper.insert(payment);
            return toResponse(payment);
        });
    }

    public PaymentResponse get(long paymentId) {
        return toResponse(requirePayment(paymentId));
    }

    public List<PaymentResponse> list() {
        return paymentMapper.selectList(new LambdaQueryWrapper<PaymentOrderEntity>()
                        .orderByDesc(PaymentOrderEntity::getCreatedAt)
                        .orderByDesc(PaymentOrderEntity::getId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public PaymentResponse close(long paymentId) {
        return lockService.withLock("payment:id:" + paymentId, () -> {
            PaymentOrderEntity payment = requirePayment(paymentId);
            if (!OPEN_PAYMENT_STATUSES.contains(payment.getStatus())) {
                throw BusinessException.conflict("payment is not open");
            }
            Instant now = Instant.now();
            payment.setStatus(PaymentStatus.CLOSED.name());
            payment.setUpdatedAt(now);
            payment.setClosedAt(now);
            updatePayment(payment);
            return toResponse(payment);
        });
    }

    public PaymentResponse handleCallback(PaymentChannel channel, PaymentCallbackRequest request) {
        return lockService.withLock("payment:id:" + request.paymentId(), () -> {
            PaymentOrderEntity payment = requirePayment(request.paymentId());
            PaymentCallbackEntity existingCallback = callbackMapper.selectOne(new LambdaQueryWrapper<PaymentCallbackEntity>()
                    .eq(PaymentCallbackEntity::getChannel, channel.name())
                    .eq(PaymentCallbackEntity::getNotifyId, request.notifyId()));
            if (existingCallback != null) {
                return toResponse(payment);
            }

            verifyCallback(channel, payment, request);
            insertCallback(channel, request);

            String event = normalizeEvent(request.status());
            if ("SUCCESS".equals(event) && !PaymentStatus.SUCCESS.name().equals(payment.getStatus())) {
                payment.setStatus(PaymentStatus.SUCCESS.name());
                payment.setChannelTradeNo(request.channelTradeNo());
                payment.setPaidAt(Instant.now());
                payment.setUpdatedAt(payment.getPaidAt());
                updatePayment(payment);
            } else if ("FAILED".equals(event) && OPEN_PAYMENT_STATUSES.contains(payment.getStatus())) {
                payment.setStatus(PaymentStatus.FAILED.name());
                payment.setChannelTradeNo(request.channelTradeNo());
                payment.setUpdatedAt(Instant.now());
                updatePayment(payment);
            }
            return toResponse(payment);
        });
    }

    public RefundResponse refund(long paymentId, RefundCreateRequest request) {
        return lockService.withLock("payment:id:" + paymentId, () -> {
            PaymentOrderEntity payment = requirePayment(paymentId);
            if (!PaymentStatus.SUCCESS.name().equals(payment.getStatus())) {
                throw BusinessException.conflict("payment is not successful");
            }
            if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest("refund amount must be positive");
            }
            if (request.amount().compareTo(payment.getAmount()) > 0) {
                throw BusinessException.badRequest("refund amount exceeds payment amount");
            }

            Instant now = Instant.now();
            RefundOrderEntity refund = new RefundOrderEntity();
            refund.setId(IdWorker.getId());
            refund.setPaymentId(paymentId);
            refund.setAmount(request.amount().setScale(2, RoundingMode.HALF_UP));
            refund.setReason((request.reason() == null || request.reason().isBlank()) ? "refund requested" : request.reason());
            refund.setStatus(RefundStatus.SUCCESS.name());
            refund.setChannelRefundNo("MOCK-REFUND-" + refund.getId());
            refund.setCreatedAt(now);
            refund.setUpdatedAt(now);
            refund.setCompletedAt(now);
            refundMapper.insert(refund);

            payment.setStatus(PaymentStatus.REFUNDED.name());
            payment.setUpdatedAt(now);
            updatePayment(payment);
            return toResponse(refund);
        });
    }

    public List<RefundResponse> listRefunds(long paymentId) {
        return refundMapper.selectList(new LambdaQueryWrapper<RefundOrderEntity>()
                        .eq(RefundOrderEntity::getPaymentId, paymentId)
                        .orderByDesc(RefundOrderEntity::getCreatedAt))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private OrderEntity requirePayableOrder(long orderId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("order not found");
        }
        if (OrderStatus.CANCELED.name().equals(order.getStatus()) || OrderStatus.TIMEOUT_CLOSED.name().equals(order.getStatus())) {
            throw BusinessException.conflict("order is not payable");
        }
        return order;
    }

    private BigDecimal amountOf(OrderEntity order) {
        return order.getPrice().multiply(BigDecimal.valueOf(order.getOriginalQuantity())).setScale(2, RoundingMode.HALF_UP);
    }

    private PaymentOrderEntity requirePayment(long paymentId) {
        PaymentOrderEntity payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw BusinessException.notFound("payment not found");
        }
        return payment;
    }

    private void verifyCallback(PaymentChannel channel, PaymentOrderEntity payment, PaymentCallbackRequest request) {
        if (!channel.name().equals(payment.getChannel())) {
            throw BusinessException.badRequest("channel mismatch");
        }
        if (!MOCK_SIGNATURE.equals(request.signature())) {
            throw BusinessException.badRequest("invalid payment signature");
        }
        if (payment.getAmount().compareTo(request.amount()) != 0) {
            throw BusinessException.badRequest("amount mismatch");
        }
        String event = normalizeEvent(request.status());
        if (!"SUCCESS".equals(event) && !"FAILED".equals(event)) {
            throw BusinessException.badRequest("unsupported payment callback status");
        }
    }

    private void insertCallback(PaymentChannel channel, PaymentCallbackRequest request) {
        PaymentCallbackEntity callback = new PaymentCallbackEntity();
        callback.setId(IdWorker.getId());
        callback.setPaymentId(request.paymentId());
        callback.setChannel(channel.name());
        callback.setNotifyId(request.notifyId());
        callback.setChannelTradeNo(request.channelTradeNo());
        callback.setEventType(normalizeEvent(request.status()));
        callback.setAmount(request.amount());
        callback.setPayload("paymentId=" + request.paymentId() + ",notifyId=" + request.notifyId() + ",status=" + request.status());
        callback.setProcessed(true);
        callback.setCreatedAt(Instant.now());
        callbackMapper.insert(callback);
    }

    private void updatePayment(PaymentOrderEntity payment) {
        int updated = paymentMapper.updateById(payment);
        if (updated == 0) {
            throw BusinessException.conflict("payment update conflicted");
        }
    }

    private String normalizeEvent(String status) {
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private String mockPayUrl(PaymentChannel channel, long paymentId) {
        return "https://xwycx.xyz/mock-pay/" + channel.name().toLowerCase(Locale.ROOT) + "?paymentId=" + paymentId;
    }

    private PaymentResponse toResponse(PaymentOrderEntity payment) {
        return new PaymentResponse(payment.getId(), payment.getOrderId(), PaymentChannel.valueOf(payment.getChannel()),
                payment.getAmount(), PaymentStatus.valueOf(payment.getStatus()), payment.getChannelTradeNo(),
                payment.getPayUrl(), payment.getQrCode(), payment.getCreatedAt(), payment.getUpdatedAt(),
                payment.getExpireAt(), payment.getPaidAt(), payment.getClosedAt());
    }

    private RefundResponse toResponse(RefundOrderEntity refund) {
        return new RefundResponse(refund.getId(), refund.getPaymentId(), refund.getAmount(), refund.getReason(),
                RefundStatus.valueOf(refund.getStatus()), refund.getChannelRefundNo(), refund.getCreatedAt(),
                refund.getUpdatedAt(), refund.getCompletedAt());
    }
}

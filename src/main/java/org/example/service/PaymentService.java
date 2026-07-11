package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.example.auth.UserRole;
import org.example.config.AppProperties;
import org.example.commerce.CustomerOrderStatus;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mybatis.entity.CustomerOrderEntity;
import org.example.infrastructure.mybatis.entity.OrderEntity;
import org.example.infrastructure.mybatis.entity.PaymentCallbackEntity;
import org.example.infrastructure.mybatis.entity.PaymentOrderEntity;
import org.example.infrastructure.mybatis.entity.RefundOrderEntity;
import org.example.infrastructure.mybatis.mapper.CustomerOrderMapper;
import org.example.infrastructure.mybatis.mapper.OrderMapper;
import org.example.infrastructure.mybatis.mapper.PaymentCallbackMapper;
import org.example.infrastructure.mybatis.mapper.PaymentOrderMapper;
import org.example.infrastructure.mybatis.mapper.RefundOrderMapper;
import org.example.payment.PaymentGateway;
import org.example.payment.PaymentGatewayResult;
import org.example.payment.PaymentChannel;
import org.example.payment.PaymentStatus;
import org.example.refund.RefundStatus;
import org.example.trade.OrderStatus;
import org.example.web.BusinessException;
import org.example.web.dto.AuthUserResponse;
import org.example.web.dto.PaymentCallbackRequest;
import org.example.web.dto.PaymentCreateRequest;
import org.example.web.dto.PaymentResponse;
import org.example.web.dto.RefundCreateRequest;
import org.example.web.dto.RefundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Set<String> OPEN_PAYMENT_STATUSES = Set.of(PaymentStatus.PENDING.name(), PaymentStatus.PAYING.name());

    private final PaymentOrderMapper paymentMapper;
    private final PaymentCallbackMapper callbackMapper;
    private final RefundOrderMapper refundMapper;
    private final OrderMapper orderMapper;
    private final CustomerOrderMapper customerOrderMapper;
    private final DistributedLockService lockService;
    private final AppProperties properties;
    private final PaymentGateway paymentGateway;

    public PaymentService(PaymentOrderMapper paymentMapper,
                          PaymentCallbackMapper callbackMapper,
                          RefundOrderMapper refundMapper,
                          OrderMapper orderMapper,
                          CustomerOrderMapper customerOrderMapper,
                          DistributedLockService lockService,
                          AppProperties properties,
                          PaymentGateway paymentGateway) {
        this.paymentMapper = paymentMapper;
        this.callbackMapper = callbackMapper;
        this.refundMapper = refundMapper;
        this.orderMapper = orderMapper;
        this.customerOrderMapper = customerOrderMapper;
        this.lockService = lockService;
        this.properties = properties;
        this.paymentGateway = paymentGateway;
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
                PaymentGatewayResult gateway = paymentGateway.createPayment(request.channel(), existing.getId(), existing.getAmount());
                existing.setPayUrl(gateway.payUrl());
                existing.setQrCode(gateway.qrCode());
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
            PaymentGatewayResult gateway = paymentGateway.createPayment(request.channel(), payment.getId(), payment.getAmount());
            payment.setPayUrl(gateway.payUrl());
            payment.setQrCode(gateway.qrCode());
            paymentMapper.insert(payment);
            log.info("payment created paymentId={} orderId={} channel={} amount={}", payment.getId(), payment.getOrderId(), payment.getChannel(), payment.getAmount());
            return toResponse(payment);
        });
    }

    public PaymentResponse createForCustomerOrder(PaymentCreateRequest request, BigDecimal amount) {
        return lockService.withLock("payment:customer-order:" + request.orderId() + ":" + request.channel(), () -> {
            CustomerOrderEntity order = customerOrderMapper.selectById(request.orderId());
            if (order == null) {
                throw BusinessException.notFound("customer order not found");
            }
            if (!CustomerOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
                throw BusinessException.conflict("customer order is not payable");
            }
            PaymentOrderEntity existing = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrderEntity>()
                    .eq(PaymentOrderEntity::getOrderId, request.orderId())
                    .eq(PaymentOrderEntity::getChannel, request.channel().name()));
            if (existing != null && (OPEN_PAYMENT_STATUSES.contains(existing.getStatus()) || PaymentStatus.SUCCESS.name().equals(existing.getStatus()))) {
                return toResponse(existing);
            }
            Instant now = Instant.now();
            PaymentOrderEntity payment = existing == null ? new PaymentOrderEntity() : existing;
            if (payment.getId() == null) {
                payment.setId(IdWorker.getId());
                payment.setCreatedAt(now);
                payment.setVersion(0L);
            }
            payment.setOrderId(order.getId());
            payment.setChannel(request.channel().name());
            payment.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
            payment.setStatus(PaymentStatus.PAYING.name());
            payment.setChannelTradeNo(null);
            payment.setPaidAt(null);
            payment.setClosedAt(null);
            payment.setUpdatedAt(now);
            payment.setExpireAt(now.plusSeconds(properties.getPayment().getTimeoutSeconds()));
            PaymentGatewayResult gateway = paymentGateway.createPayment(request.channel(), payment.getId(), payment.getAmount());
            payment.setPayUrl(gateway.payUrl());
            payment.setQrCode(gateway.qrCode());
            if (existing == null) {
                paymentMapper.insert(payment);
            } else {
                updatePayment(payment);
            }
            log.info("customer payment created paymentId={} customerOrderId={} channel={} amount={}", payment.getId(), payment.getOrderId(), payment.getChannel(), payment.getAmount());
            return toResponse(payment);
        });
    }

    public PaymentResponse get(long paymentId) {
        return toResponse(requirePayment(paymentId));
    }

    public PaymentResponse get(AuthUserResponse user, long paymentId) {
        PaymentOrderEntity payment = requirePayment(paymentId);
        requireVisiblePayment(user, payment);
        return toResponse(payment);
    }

    public List<PaymentResponse> list() {
        return paymentMapper.selectList(new LambdaQueryWrapper<PaymentOrderEntity>()
                        .orderByDesc(PaymentOrderEntity::getCreatedAt)
                        .orderByDesc(PaymentOrderEntity::getId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<PaymentResponse> list(AuthUserResponse user) {
        LambdaQueryWrapper<PaymentOrderEntity> query = new LambdaQueryWrapper<PaymentOrderEntity>()
                .orderByDesc(PaymentOrderEntity::getCreatedAt)
                .orderByDesc(PaymentOrderEntity::getId);
        if (user.role() != UserRole.ADMIN) {
            List<Long> orderIds = customerOrderMapper.selectList(new LambdaQueryWrapper<CustomerOrderEntity>()
                            .eq(CustomerOrderEntity::getUserId, user.id()))
                    .stream()
                    .map(CustomerOrderEntity::getId)
                    .toList();
            if (orderIds.isEmpty()) {
                return List.of();
            }
            query.in(PaymentOrderEntity::getOrderId, orderIds);
        }
        return paymentMapper.selectList(query).stream().map(this::toResponse).toList();
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

    public PaymentResponse close(AuthUserResponse user, long paymentId) {
        requireVisiblePayment(user, requirePayment(paymentId));
        return close(paymentId);
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
            log.info("payment callback accepted paymentId={} channel={} notifyId={} status={}", payment.getId(), channel, request.notifyId(), request.status());

            String event = normalizeEvent(request.status());
            if ("SUCCESS".equals(event) && !PaymentStatus.SUCCESS.name().equals(payment.getStatus())) {
                payment.setStatus(PaymentStatus.SUCCESS.name());
                payment.setChannelTradeNo(request.channelTradeNo());
                payment.setPaidAt(Instant.now());
                payment.setUpdatedAt(payment.getPaidAt());
                updatePayment(payment);
                markCustomerOrderPaid(payment.getOrderId(), payment.getPaidAt());
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
            refund.setChannelRefundNo(paymentGateway.createRefundNo(refund.getId()));
            refund.setCreatedAt(now);
            refund.setUpdatedAt(now);
            refund.setCompletedAt(now);
            refundMapper.insert(refund);

            payment.setStatus(PaymentStatus.REFUNDED.name());
            payment.setUpdatedAt(now);
            updatePayment(payment);
            log.info("payment refunded paymentId={} refundId={} amount={}", paymentId, refund.getId(), refund.getAmount());
            return toResponse(refund);
        });
    }

    public RefundResponse refund(AuthUserResponse user, long paymentId, RefundCreateRequest request) {
        requireVisiblePayment(user, requirePayment(paymentId));
        return refund(paymentId, request);
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

    private void requireVisiblePayment(AuthUserResponse user, PaymentOrderEntity payment) {
        if (user.role() == UserRole.ADMIN) {
            return;
        }
        CustomerOrderEntity order = customerOrderMapper.selectById(payment.getOrderId());
        if (order == null || !order.getUserId().equals(user.id())) {
            throw BusinessException.forbidden("payment belongs to another user");
        }
    }

    private void verifyCallback(PaymentChannel channel, PaymentOrderEntity payment, PaymentCallbackRequest request) {
        if (!channel.name().equals(payment.getChannel())) {
            throw BusinessException.badRequest("channel mismatch");
        }
        if (!paymentGateway.verifyCallback(channel, request.signature())) {
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

    private void markCustomerOrderPaid(long orderId, Instant paidAt) {
        CustomerOrderEntity order = customerOrderMapper.selectById(orderId);
        if (order != null && CustomerOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            order.setStatus(CustomerOrderStatus.PAID.name());
            order.setPaidAt(paidAt);
            order.setUpdatedAt(paidAt);
            customerOrderMapper.updateById(order);
        }
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

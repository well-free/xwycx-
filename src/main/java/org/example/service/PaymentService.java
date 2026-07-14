package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.example.infrastructure.mybatis.mapper.WechatIdentityMapper;
import org.example.payment.PaymentGateway;
import org.example.payment.PaymentGatewayResult;
import org.example.payment.PaymentGatewayRequest;
import org.example.payment.MiniProgramPaymentParameters;
import org.example.payment.PaymentRefundResult;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Set<String> OPEN_PAYMENT_STATUSES = Set.of(PaymentStatus.PENDING.name(), PaymentStatus.PAYING.name());
    private static final Set<String> CALLBACK_TERMINAL_STATUSES = Set.of(
            PaymentStatus.CLOSED.name(), PaymentStatus.REFUNDING.name(), PaymentStatus.REFUNDED.name());

    private final PaymentOrderMapper paymentMapper;
    private final PaymentCallbackMapper callbackMapper;
    private final RefundOrderMapper refundMapper;
    private final OrderMapper orderMapper;
    private final CustomerOrderMapper customerOrderMapper;
    private final WechatIdentityMapper wechatIdentityMapper;
    private final DistributedLockService lockService;
    private final AppProperties properties;
    private final PaymentGateway paymentGateway;
    private final CustomerOrderLifecycleService customerOrderLifecycleService;
    private final PaymentCompensationService paymentCompensationService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate callbackTransactionTemplate;

    public PaymentService(PaymentOrderMapper paymentMapper,
                          PaymentCallbackMapper callbackMapper,
                          RefundOrderMapper refundMapper,
                          OrderMapper orderMapper,
                          CustomerOrderMapper customerOrderMapper,
                          WechatIdentityMapper wechatIdentityMapper,
                          DistributedLockService lockService,
                          AppProperties properties,
                          PaymentGateway paymentGateway,
                          CustomerOrderLifecycleService customerOrderLifecycleService,
                          PaymentCompensationService paymentCompensationService,
                          ObjectMapper objectMapper,
                          TransactionTemplate transactionTemplate) {
        this.paymentMapper = paymentMapper;
        this.callbackMapper = callbackMapper;
        this.refundMapper = refundMapper;
        this.orderMapper = orderMapper;
        this.customerOrderMapper = customerOrderMapper;
        this.wechatIdentityMapper = wechatIdentityMapper;
        this.lockService = lockService;
        this.properties = properties;
        this.paymentGateway = paymentGateway;
        this.customerOrderLifecycleService = customerOrderLifecycleService;
        this.paymentCompensationService = paymentCompensationService;
        this.objectMapper = objectMapper;
        this.callbackTransactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        this.callbackTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
                existing.setGatewayMode(paymentMode());
                existing.setStatus(PaymentStatus.PAYING.name());
                existing.setChannelTradeNo(null);
                existing.setPaidAt(null);
                existing.setClosedAt(null);
                existing.setUpdatedAt(now);
                existing.setExpireAt(now.plusSeconds(properties.getPayment().getTimeoutSeconds()));
                PaymentGatewayResult gateway = paymentGateway.createPayment(request.channel(), existing.getId(), existing.getAmount());
                applyGatewayResult(existing, gateway);
                updatePayment(existing);
                return toResponse(existing);
            }

            Instant now = Instant.now();
            PaymentOrderEntity payment = new PaymentOrderEntity();
            payment.setId(IdWorker.getId());
            payment.setOrderId(order.getId());
            payment.setGatewayMode(paymentMode());
            payment.setChannel(request.channel().name());
            payment.setAmount(amountOf(order));
            payment.setStatus(PaymentStatus.PAYING.name());
            payment.setVersion(0L);
            payment.setCreatedAt(now);
            payment.setUpdatedAt(now);
            payment.setExpireAt(now.plusSeconds(properties.getPayment().getTimeoutSeconds()));
            PaymentGatewayResult gateway = paymentGateway.createPayment(request.channel(), payment.getId(), payment.getAmount());
            applyGatewayResult(payment, gateway);
            paymentMapper.insert(payment);
            log.info("payment created paymentId={} orderId={} channel={} amount={}", payment.getId(), payment.getOrderId(), payment.getChannel(), payment.getAmount());
            return toResponse(payment);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentResponse createForCustomerOrder(PaymentCreateRequest request, BigDecimal amount) {
        CustomerOrderEntity order = customerOrderMapper.selectById(request.orderId());
        if (order == null) {
            throw BusinessException.notFound("customer order not found");
        }
        if (!CustomerOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            throw BusinessException.conflict("customer order is not payable");
        }
        PaymentOrderEntity activePayment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrderEntity>()
                .eq(PaymentOrderEntity::getOrderId, request.orderId())
                .in(PaymentOrderEntity::getStatus, OPEN_PAYMENT_STATUSES)
                .orderByDesc(PaymentOrderEntity::getCreatedAt)
                .last("limit 1"));
        if (activePayment != null) {
            return toResponse(activePayment);
        }
        PaymentOrderEntity existing = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrderEntity>()
                .eq(PaymentOrderEntity::getOrderId, request.orderId())
                .eq(PaymentOrderEntity::getChannel, request.channel().name())
                .orderByDesc(PaymentOrderEntity::getCreatedAt)
                .last("limit 1"));
        Instant now = Instant.now();
        PaymentOrderEntity payment = existing == null ? new PaymentOrderEntity() : existing;
        if (payment.getId() == null) {
            payment.setId(IdWorker.getId());
            payment.setCreatedAt(now);
            payment.setVersion(0L);
        }
        payment.setOrderId(order.getId());
        payment.setGatewayMode(paymentMode());
        payment.setChannel(request.channel().name());
        payment.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        payment.setStatus(PaymentStatus.PAYING.name());
        payment.setChannelTradeNo(null);
        payment.setPaidAt(null);
        payment.setClosedAt(null);
        payment.setUpdatedAt(now);
        payment.setExpireAt(now.plusSeconds(properties.getPayment().getTimeoutSeconds()));
        PaymentGatewayResult gateway = paymentGateway.createPayment(new PaymentGatewayRequest(
                request.channel(), payment.getId(), payment.getAmount(),
                payerOpenId(order.getUserId(), request.channel())));
        applyGatewayResult(payment, gateway);
        if (existing == null) {
            if (paymentMapper.insert(payment) != 1) {
                throw BusinessException.conflict("payment insert conflicted");
            }
        } else {
            updatePayment(payment);
        }
        log.info("customer payment created paymentId={} customerOrderId={} channel={} amount={}", payment.getId(), payment.getOrderId(), payment.getChannel(), payment.getAmount());
        return toResponse(payment);
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

    public PaymentResponse simulateSuccess(AuthUserResponse user, long paymentId) {
        PaymentOrderEntity payment = requirePayment(paymentId);
        requireVisiblePayment(user, payment);
        String mode = gatewayModeOf(payment);
        if (!"mock".equalsIgnoreCase(mode) && !"sandbox".equalsIgnoreCase(mode)) {
            throw BusinessException.conflict("payment simulation is disabled");
        }
        PaymentChannel channel = PaymentChannel.valueOf(payment.getChannel());
        return handleCallback(channel, new PaymentCallbackRequest(
                payment.getId(),
                "SIM-" + payment.getId(),
                "SIM-" + channel.name() + "-" + payment.getId(),
                payment.getAmount(),
                "SUCCESS",
                properties.getPayment().getCallbackSecret()
        ));
    }

    public PaymentResponse handleCallback(PaymentChannel channel, PaymentCallbackRequest request) {
        return handleCallback(channel, request, false);
    }

    public PaymentResponse handleVerifiedCallback(PaymentChannel channel, PaymentCallbackRequest request) {
        return handleCallback(channel, request, true);
    }

    public void handleVerifiedRefund(long refundId, String channelRefundNo, RefundStatus status) {
        paymentCompensationService.handleGatewayNotification(refundId, channelRefundNo, status);
    }

    private PaymentResponse handleCallback(PaymentChannel channel,
                                           PaymentCallbackRequest request,
                                           boolean signatureVerified) {
        PaymentResponse response = lockService.withLock("payment:id:" + request.paymentId(), () -> {
            PaymentOrderEntity payment = requirePayment(request.paymentId());
            Supplier<PaymentResponse> transaction = () -> callbackTransactionTemplate.execute(status ->
                    processCallback(channel, request, signatureVerified));
            CustomerOrderEntity customerOrder = customerOrderMapper.selectById(payment.getOrderId());
            return customerOrder == null
                    ? transaction.get()
                    : customerOrderLifecycleService.withOrderLock(customerOrder.getId(), transaction);
        });
        paymentCompensationService.attemptForPayment(response.orderId(), response.id());
        return response;
    }

    private PaymentResponse processCallback(PaymentChannel channel,
                                            PaymentCallbackRequest request,
                                            boolean signatureVerified) {
        PaymentOrderEntity payment = requirePayment(request.paymentId());
        PaymentCallbackEntity existingCallback = callbackMapper.selectOne(new LambdaQueryWrapper<PaymentCallbackEntity>()
                .eq(PaymentCallbackEntity::getChannel, channel.name())
                .eq(PaymentCallbackEntity::getNotifyId, request.notifyId()));
        if (existingCallback != null) {
            return toResponse(payment);
        }

        verifyCallback(channel, payment, request, signatureVerified);
        insertCallback(channel, request);
        log.info("payment callback accepted paymentId={} channel={} notifyId={} status={}",
                payment.getId(), channel, request.notifyId(), request.status());

        String event = normalizeEvent(request.status());
        if ("SUCCESS".equals(event)) {
            if (CALLBACK_TERMINAL_STATUSES.contains(payment.getStatus())) {
                log.info("payment success callback ignored for terminal payment paymentId={} status={}",
                        payment.getId(), payment.getStatus());
                return toResponse(payment);
            }
            Instant paidAt = payment.getPaidAt() == null ? Instant.now() : payment.getPaidAt();
            if (hasOtherSuccessfulCustomerPayment(payment)) {
                payment.setStatus(PaymentStatus.REFUNDING.name());
                payment.setChannelTradeNo(request.channelTradeNo());
                payment.setPaidAt(paidAt);
                payment.setUpdatedAt(paidAt);
                updatePayment(payment);
                log.warn("duplicate customer payment scheduled for refund paymentId={} orderId={}",
                        payment.getId(), payment.getOrderId());
                return toResponse(payment);
            }
            if (!PaymentStatus.SUCCESS.name().equals(payment.getStatus())) {
                payment.setStatus(PaymentStatus.SUCCESS.name());
                payment.setChannelTradeNo(request.channelTradeNo());
                payment.setPaidAt(paidAt);
                payment.setUpdatedAt(paidAt);
                updatePayment(payment);
            }
            customerOrderLifecycleService.applySuccessfulPaymentLocked(payment.getOrderId(), paidAt);
        } else if ("FAILED".equals(event) && OPEN_PAYMENT_STATUSES.contains(payment.getStatus())) {
            payment.setStatus(PaymentStatus.FAILED.name());
            payment.setChannelTradeNo(request.channelTradeNo());
            payment.setUpdatedAt(Instant.now());
            updatePayment(payment);
        }
        return toResponse(payment);
    }

    private boolean hasOtherSuccessfulCustomerPayment(PaymentOrderEntity payment) {
        if (customerOrderMapper.selectById(payment.getOrderId()) == null) {
            return false;
        }
        return paymentMapper.selectCount(new LambdaQueryWrapper<PaymentOrderEntity>()
                .eq(PaymentOrderEntity::getOrderId, payment.getOrderId())
                .ne(PaymentOrderEntity::getId, payment.getId())
                .eq(PaymentOrderEntity::getStatus, PaymentStatus.SUCCESS.name())) > 0;
    }

    public RefundResponse refund(long paymentId, RefundCreateRequest request) {
        return lockService.withLock("payment:id:" + paymentId, () ->
                callbackTransactionTemplate.execute(status -> refundLocked(paymentId, request)));
    }

    public RefundResponse refundCustomerOrder(AuthUserResponse user,
                                              long orderId,
                                              RefundCreateRequest request) {
        CustomerOrderEntity visibleOrder = requireVisibleCustomerOrder(user, orderId);
        PaymentOrderEntity visiblePayment = findSuccessfulPayment(orderId);
        if (visiblePayment == null) {
            throw BusinessException.conflict("order is not paid");
        }
        return lockService.withLock("payment:id:" + visiblePayment.getId(), () ->
                customerOrderLifecycleService.withOrderLock(orderId, () ->
                        callbackTransactionTemplate.execute(status -> {
                            CustomerOrderEntity order = requireVisibleCustomerOrder(user, visibleOrder.getId());
                            PaymentOrderEntity payment = requirePayment(visiblePayment.getId());
                            if (!payment.getOrderId().equals(orderId)) {
                                throw BusinessException.conflict("payment order mismatch");
                            }
                            RefundResponse refund = refundLocked(payment.getId(), request);
                            PaymentOrderEntity refundedPayment = requirePayment(payment.getId());
                            String nextOrderStatus = order.getStatus();
                            if (PaymentStatus.REFUNDED.name().equals(refundedPayment.getStatus())) {
                                nextOrderStatus = CustomerOrderStatus.REFUNDED.name();
                            } else if (PaymentStatus.REFUNDING.name().equals(refundedPayment.getStatus())
                                    && pendingRefundTotal(payment.getId()).compareTo(payment.getAmount()) >= 0) {
                                nextOrderStatus = CustomerOrderStatus.REFUNDING.name();
                            }
                            if (nextOrderStatus.equals(order.getStatus())) {
                                return refund;
                            }
                            order.setStatus(nextOrderStatus);
                            order.setUpdatedAt(Instant.now());
                            if (customerOrderMapper.updateById(order) != 1) {
                                throw BusinessException.conflict("customer order update conflicted");
                            }
                            return refund;
                        })));
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

    public List<RefundResponse> listRefunds() {
        return refundMapper.selectList(new LambdaQueryWrapper<RefundOrderEntity>()
                        .orderByDesc(RefundOrderEntity::getCreatedAt))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public RefundResponse approveRefund(long refundId) {
        RefundOrderEntity refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw BusinessException.notFound("refund not found");
        }
        if (RefundStatus.SUCCESS.name().equals(refund.getStatus())) {
            return toResponse(refund);
        }
        PaymentOrderEntity payment = requirePayment(refund.getPaymentId());
        if (customerOrderMapper.selectById(payment.getOrderId()) != null) {
            paymentCompensationService.attemptForPayment(payment.getOrderId(), payment.getId());
        }
        return toResponse(refundMapper.selectById(refundId));
    }

    private RefundResponse refundLocked(long paymentId, RefundCreateRequest request) {
        PaymentOrderEntity payment = requirePayment(paymentId);
        if (!PaymentStatus.SUCCESS.name().equals(payment.getStatus())) {
            throw BusinessException.conflict("payment is not successful");
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("refund amount must be positive");
        }
        BigDecimal completedRefunds = completedRefundTotal(paymentId);
        BigDecimal availableRefund = payment.getAmount().subtract(completedRefunds);
        if (request.amount().compareTo(availableRefund) > 0) {
            throw BusinessException.badRequest("refund amount exceeds payment amount");
        }

        Instant now = Instant.now();
        RefundOrderEntity refund = new RefundOrderEntity();
        refund.setId(IdWorker.getId());
        refund.setPaymentId(paymentId);
        refund.setAmount(request.amount().setScale(2, RoundingMode.HALF_UP));
        refund.setReason((request.reason() == null || request.reason().isBlank()) ? "refund requested" : request.reason());
        PaymentRefundResult gatewayRefund = paymentGateway.refund(
                PaymentChannel.valueOf(payment.getChannel()), refund.getId(), paymentId,
                refund.getAmount(), payment.getAmount(), payment.getChannelTradeNo());
        refund.setStatus(gatewayRefund.status().name());
        refund.setChannelRefundNo(gatewayRefund.channelRefundNo());
        refund.setCreatedAt(now);
        refund.setUpdatedAt(now);
        if (gatewayRefund.status() == RefundStatus.SUCCESS) {
            refund.setCompletedAt(now);
        }
        if (refundMapper.insert(refund) != 1) {
            throw BusinessException.conflict("refund insert conflicted");
        }

        boolean fullyRefunded = completedRefunds.add(refund.getAmount()).compareTo(payment.getAmount()) >= 0;
        payment.setStatus(gatewayRefund.status() == RefundStatus.SUCCESS
                ? (fullyRefunded ? PaymentStatus.REFUNDED.name() : PaymentStatus.SUCCESS.name())
                : PaymentStatus.REFUNDING.name());
        payment.setUpdatedAt(now);
        updatePayment(payment);
        log.info("payment refunded paymentId={} refundId={} amount={}", paymentId, refund.getId(), refund.getAmount());
        return toResponse(refund);
    }

    private BigDecimal completedRefundTotal(long paymentId) {
        return refundTotal(paymentId, Set.of(RefundStatus.SUCCESS.name()));
    }

    private BigDecimal pendingRefundTotal(long paymentId) {
        return refundTotal(paymentId, Set.of(
                RefundStatus.SUCCESS.name(), RefundStatus.PROCESSING.name(), RefundStatus.FAILED.name()));
    }

    private BigDecimal refundTotal(long paymentId, Set<String> statuses) {
        return refundMapper.selectList(new LambdaQueryWrapper<RefundOrderEntity>()
                        .eq(RefundOrderEntity::getPaymentId, paymentId)
                        .in(RefundOrderEntity::getStatus, statuses))
                .stream()
                .map(RefundOrderEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private CustomerOrderEntity requireVisibleCustomerOrder(AuthUserResponse user, long orderId) {
        CustomerOrderEntity order = customerOrderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("customer order not found");
        }
        if (user.role() != UserRole.ADMIN && !order.getUserId().equals(user.id())) {
            throw BusinessException.forbidden("order belongs to another user");
        }
        return order;
    }

    private PaymentOrderEntity findSuccessfulPayment(long orderId) {
        return paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrderEntity>()
                .eq(PaymentOrderEntity::getOrderId, orderId)
                .eq(PaymentOrderEntity::getStatus, PaymentStatus.SUCCESS.name())
                .orderByDesc(PaymentOrderEntity::getPaidAt)
                .last("limit 1"));
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

    private void verifyCallback(PaymentChannel channel,
                                PaymentOrderEntity payment,
                                PaymentCallbackRequest request,
                                boolean signatureVerified) {
        if (!channel.name().equals(payment.getChannel())) {
            throw BusinessException.badRequest("channel mismatch");
        }
        if (!signatureVerified && !paymentGateway.verifyCallback(channel, request.signature())) {
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

    private String payerOpenId(long userId, PaymentChannel channel) {
        if (channel != PaymentChannel.WECHAT) {
            return null;
        }
        var identity = wechatIdentityMapper.selectOne(
                new LambdaQueryWrapper<org.example.infrastructure.mybatis.entity.WechatIdentityEntity>()
                        .eq(org.example.infrastructure.mybatis.entity.WechatIdentityEntity::getUserId, userId)
                        .eq(org.example.infrastructure.mybatis.entity.WechatIdentityEntity::getAppid,
                                properties.getWechat().getAppId())
                        .orderByDesc(org.example.infrastructure.mybatis.entity.WechatIdentityEntity::getUpdatedAt)
                        .last("limit 1"));
        return identity == null ? null : identity.getOpenid();
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
        if (callbackMapper.insert(callback) != 1) {
            throw BusinessException.conflict("payment callback insert conflicted");
        }
    }

    private void updatePayment(PaymentOrderEntity payment) {
        int updated = paymentMapper.updateById(payment);
        if (updated != 1) {
            throw BusinessException.conflict("payment update conflicted");
        }
    }

    private void applyGatewayResult(PaymentOrderEntity payment, PaymentGatewayResult gateway) {
        payment.setPayUrl(gateway.payUrl());
        payment.setQrCode(gateway.qrCode());
        payment.setPrepayId(gateway.prepayId());
        payment.setPaymentParameters(writeMiniProgramParameters(gateway.miniProgram()));
    }

    private String writeMiniProgramParameters(MiniProgramPaymentParameters parameters) {
        if (parameters == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(parameters);
        } catch (JsonProcessingException exception) {
            throw BusinessException.serviceUnavailable("payment parameters serialization failed");
        }
    }

    private MiniProgramPaymentParameters readMiniProgramParameters(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, MiniProgramPaymentParameters.class);
        } catch (JsonProcessingException exception) {
            log.warn("invalid stored mini program payment parameters");
            return null;
        }
    }

    private String normalizeEvent(String status) {
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private PaymentResponse toResponse(PaymentOrderEntity payment) {
        return new PaymentResponse(payment.getId(), payment.getOrderId(), gatewayModeOf(payment),
                PaymentChannel.valueOf(payment.getChannel()), payment.getAmount(), PaymentStatus.valueOf(payment.getStatus()), payment.getChannelTradeNo(),
                payment.getPayUrl(), payment.getQrCode(), payment.getPrepayId(),
                readMiniProgramParameters(payment.getPaymentParameters()), payment.getCreatedAt(), payment.getUpdatedAt(),
                payment.getExpireAt(), payment.getPaidAt(), payment.getClosedAt());
    }

    private String paymentMode() {
        String mode = properties.getPayment().getMode();
        return mode == null || mode.isBlank() ? "mock" : mode.trim().toLowerCase(Locale.ROOT);
    }

    private String gatewayModeOf(PaymentOrderEntity payment) {
        return payment.getGatewayMode() == null || payment.getGatewayMode().isBlank()
                ? paymentMode()
                : payment.getGatewayMode();
    }

    private RefundResponse toResponse(RefundOrderEntity refund) {
        return new RefundResponse(refund.getId(), refund.getPaymentId(), refund.getAmount(), refund.getReason(),
                RefundStatus.valueOf(refund.getStatus()), refund.getChannelRefundNo(), refund.getCreatedAt(),
                refund.getUpdatedAt(), refund.getCompletedAt());
    }
}

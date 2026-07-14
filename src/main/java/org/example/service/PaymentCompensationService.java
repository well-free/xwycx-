package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.example.commerce.CustomerOrderStatus;
import org.example.config.AppProperties;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mybatis.entity.CustomerOrderEntity;
import org.example.infrastructure.mybatis.entity.PaymentOrderEntity;
import org.example.infrastructure.mybatis.entity.RefundOrderEntity;
import org.example.infrastructure.mybatis.mapper.CustomerOrderMapper;
import org.example.infrastructure.mybatis.mapper.PaymentOrderMapper;
import org.example.infrastructure.mybatis.mapper.RefundOrderMapper;
import org.example.payment.PaymentChannel;
import org.example.payment.PaymentGateway;
import org.example.payment.PaymentRefundResult;
import org.example.payment.PaymentStatus;
import org.example.refund.RefundStatus;
import org.example.web.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class PaymentCompensationService {
    private static final Logger log = LoggerFactory.getLogger(PaymentCompensationService.class);
    private static final String CANCELED_ORDER_REASON = "automatic canceled-order compensation";
    private static final String DUPLICATE_PAYMENT_REASON = "automatic duplicate-payment compensation";

    private final CustomerOrderMapper orderMapper;
    private final PaymentOrderMapper paymentMapper;
    private final RefundOrderMapper refundMapper;
    private final PaymentGateway paymentGateway;
    private final DistributedLockService lockService;
    private final CustomerOrderLifecycleService lifecycleService;
    private final AppProperties properties;
    private final TransactionTemplate transactionTemplate;

    public PaymentCompensationService(CustomerOrderMapper orderMapper,
                                      PaymentOrderMapper paymentMapper,
                                      RefundOrderMapper refundMapper,
                                      PaymentGateway paymentGateway,
                                      DistributedLockService lockService,
                                      CustomerOrderLifecycleService lifecycleService,
                                      AppProperties properties,
                                      TransactionTemplate transactionTemplate) {
        this.orderMapper = orderMapper;
        this.paymentMapper = paymentMapper;
        this.refundMapper = refundMapper;
        this.paymentGateway = paymentGateway;
        this.lockService = lockService;
        this.lifecycleService = lifecycleService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean attemptForOrder(long orderId) {
        List<PaymentOrderEntity> refundingPayments = paymentMapper.selectList(
                new LambdaQueryWrapper<PaymentOrderEntity>()
                        .eq(PaymentOrderEntity::getOrderId, orderId)
                        .eq(PaymentOrderEntity::getStatus, PaymentStatus.REFUNDING.name())
                        .orderByDesc(PaymentOrderEntity::getPaidAt));
        boolean completed = false;
        for (PaymentOrderEntity payment : refundingPayments) {
            completed |= attemptForPayment(orderId, payment.getId());
        }
        if (!refundingPayments.isEmpty()) {
            return completed;
        }
        PaymentOrderEntity payment = findSuccessfulPaymentForRefundingOrder(orderId);
        return payment != null && attemptForPayment(orderId, payment.getId());
    }

    public boolean attemptForPayment(long orderId, long paymentId) {
        return lockService.withLock("payment:id:" + paymentId, () ->
                lifecycleService.withOrderLock(orderId, () -> attemptLocked(orderId, paymentId)));
    }

    @Scheduled(fixedDelayString = "${app.payment.compensation-retry-interval-ms:60000}")
    public void scheduledRetry() {
        if (properties.getPayment().isCompensationEnabled()) {
            retryRefundingOrders();
        }
    }

    public void retryRefundingOrders() {
        List<PaymentOrderEntity> payments = paymentMapper.selectList(new LambdaQueryWrapper<PaymentOrderEntity>()
                .eq(PaymentOrderEntity::getStatus, PaymentStatus.REFUNDING.name()));
        for (PaymentOrderEntity payment : payments) {
            attemptForPayment(payment.getOrderId(), payment.getId());
        }
        List<CustomerOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<CustomerOrderEntity>()
                .eq(CustomerOrderEntity::getStatus, CustomerOrderStatus.REFUNDING.name()));
        for (CustomerOrderEntity order : orders) {
            attemptForOrder(order.getId());
        }
    }

    private boolean attemptLocked(long orderId, long paymentId) {
        Compensation refund = transactionTemplate.execute(status -> prepare(orderId, paymentId));
        if (refund == null) {
            return false;
        }
        if (RefundStatus.SUCCESS.name().equals(refund.status())) {
            finish(orderId, paymentId, refund.refundId(), refund.refundOrder(),
                    new PaymentRefundResult(refund.channelRefundNo(), RefundStatus.SUCCESS));
            return true;
        }
        try {
            PaymentRefundResult result = paymentGateway.refund(
                    refund.channel(), refund.refundId(), paymentId, refund.amount(), refund.totalAmount(),
                    refund.channelTradeNo());
            if (result.status() == RefundStatus.SUCCESS) {
                finish(orderId, paymentId, refund.refundId(), refund.refundOrder(), result);
                return true;
            }
            recordGatewayState(refund.refundId(), result);
        } catch (RuntimeException exception) {
            log.warn("customer order compensation failed orderId={} paymentId={} error={}",
                    orderId, paymentId, exception.getMessage());
        }
        return false;
    }

    private Compensation prepare(long orderId, long paymentId) {
        CustomerOrderEntity order = orderMapper.selectById(orderId);
        PaymentOrderEntity payment = paymentMapper.selectById(paymentId);
        boolean refundOrder = order != null && CustomerOrderStatus.REFUNDING.name().equals(order.getStatus());
        if (order == null || payment == null
                || (!refundOrder && !PaymentStatus.REFUNDING.name().equals(payment.getStatus()))
                || (!PaymentStatus.SUCCESS.name().equals(payment.getStatus())
                && !PaymentStatus.REFUNDING.name().equals(payment.getStatus()))) {
            return null;
        }
        RefundOrderEntity refund = refundMapper.selectOne(new LambdaQueryWrapper<RefundOrderEntity>()
                .eq(RefundOrderEntity::getPaymentId, paymentId)
                .orderByDesc(RefundOrderEntity::getCreatedAt)
                .last("limit 1"));
        if (refund == null) {
            Instant now = Instant.now();
            refund = new RefundOrderEntity();
            refund.setId(IdWorker.getId());
            refund.setPaymentId(paymentId);
            refund.setAmount(payment.getAmount());
            refund.setReason(refundOrder ? CANCELED_ORDER_REASON : DUPLICATE_PAYMENT_REASON);
            refund.setStatus(RefundStatus.PROCESSING.name());
            refund.setCreatedAt(now);
            refund.setUpdatedAt(now);
            if (refundMapper.insert(refund) != 1) {
                throw BusinessException.conflict("compensation refund insert conflicted");
            }
        }
        return new Compensation(refund.getId(), refund.getAmount(), payment.getAmount(),
                PaymentChannel.valueOf(payment.getChannel()), payment.getChannelTradeNo(),
                refund.getStatus(), refund.getChannelRefundNo(), refundOrder);
    }

    private void finish(long orderId,
                        long paymentId,
                        long refundId,
                        boolean refundOrder,
                        PaymentRefundResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            RefundOrderEntity refund = refundMapper.selectById(refundId);
            PaymentOrderEntity payment = paymentMapper.selectById(paymentId);
            CustomerOrderEntity order = orderMapper.selectById(orderId);
            if (refund == null || payment == null || order == null) {
                throw BusinessException.conflict("compensation state is missing");
            }
            if (!RefundStatus.SUCCESS.name().equals(refund.getStatus())) {
                refund.setStatus(RefundStatus.SUCCESS.name());
                refund.setChannelRefundNo(result.channelRefundNo());
                refund.setUpdatedAt(now);
                refund.setCompletedAt(now);
                requireSingleUpdate(refundMapper.updateById(refund), "compensation refund update conflicted");
            }
            if (!PaymentStatus.REFUNDED.name().equals(payment.getStatus())) {
                boolean fullyRefunded = completedRefundTotal(paymentId).compareTo(payment.getAmount()) >= 0;
                int paymentUpdated = paymentMapper.update(null, new LambdaUpdateWrapper<PaymentOrderEntity>()
                        .eq(PaymentOrderEntity::getId, paymentId)
                        .in(PaymentOrderEntity::getStatus, PaymentStatus.SUCCESS.name(), PaymentStatus.REFUNDING.name())
                        .set(PaymentOrderEntity::getStatus, fullyRefunded
                                ? PaymentStatus.REFUNDED.name() : PaymentStatus.SUCCESS.name())
                        .set(PaymentOrderEntity::getUpdatedAt, now)
                        .setSql("version = version + 1"));
                requireSingleUpdate(paymentUpdated, "compensation payment update conflicted");
            }
            if (refundOrder
                    && completedRefundTotal(paymentId).compareTo(payment.getAmount()) >= 0
                    && !CustomerOrderStatus.REFUNDED.name().equals(order.getStatus())) {
                int orderUpdated = orderMapper.update(null, new LambdaUpdateWrapper<CustomerOrderEntity>()
                        .eq(CustomerOrderEntity::getId, orderId)
                        .eq(CustomerOrderEntity::getStatus, CustomerOrderStatus.REFUNDING.name())
                        .set(CustomerOrderEntity::getStatus, CustomerOrderStatus.REFUNDED.name())
                        .set(CustomerOrderEntity::getUpdatedAt, now)
                        .setSql("version = version + 1"));
                requireSingleUpdate(orderUpdated, "compensation order update conflicted");
            }
        });
    }

    private void recordGatewayState(long refundId, PaymentRefundResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            RefundOrderEntity refund = refundMapper.selectById(refundId);
            if (refund == null || RefundStatus.SUCCESS.name().equals(refund.getStatus())) {
                return;
            }
            refund.setStatus(result.status().name());
            refund.setChannelRefundNo(result.channelRefundNo());
            refund.setUpdatedAt(Instant.now());
            requireSingleUpdate(refundMapper.updateById(refund), "compensation refund update conflicted");
        });
    }

    private PaymentOrderEntity findSuccessfulPaymentForRefundingOrder(long orderId) {
        CustomerOrderEntity order = orderMapper.selectById(orderId);
        if (order == null || !CustomerOrderStatus.REFUNDING.name().equals(order.getStatus())) {
            return null;
        }
        return paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrderEntity>()
                .eq(PaymentOrderEntity::getOrderId, orderId)
                .eq(PaymentOrderEntity::getStatus, PaymentStatus.SUCCESS.name())
                .orderByDesc(PaymentOrderEntity::getPaidAt)
                .last("limit 1"));
    }

    private BigDecimal completedRefundTotal(long paymentId) {
        return refundMapper.selectList(new LambdaQueryWrapper<RefundOrderEntity>()
                        .eq(RefundOrderEntity::getPaymentId, paymentId)
                        .eq(RefundOrderEntity::getStatus, RefundStatus.SUCCESS.name()))
                .stream()
                .map(RefundOrderEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void requireSingleUpdate(int updated, String message) {
        if (updated != 1) {
            throw BusinessException.conflict(message);
        }
    }

    private record Compensation(long refundId,
                                BigDecimal amount,
                                BigDecimal totalAmount,
                                PaymentChannel channel,
                                String channelTradeNo,
                                String status,
                                String channelRefundNo,
                                boolean refundOrder) {
    }
}

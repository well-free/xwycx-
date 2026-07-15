package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.example.auth.UserRole;
import org.example.commerce.CustomerOrderStatus;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mybatis.entity.CustomerOrderEntity;
import org.example.infrastructure.mybatis.entity.CustomerOrderItemEntity;
import org.example.infrastructure.mybatis.entity.InventoryLogEntity;
import org.example.infrastructure.mybatis.entity.PaymentOrderEntity;
import org.example.infrastructure.mybatis.mapper.CustomerOrderItemMapper;
import org.example.infrastructure.mybatis.mapper.CustomerOrderMapper;
import org.example.infrastructure.mybatis.mapper.InventoryLogMapper;
import org.example.infrastructure.mybatis.mapper.PaymentOrderMapper;
import org.example.infrastructure.mybatis.mapper.ProductMapper;
import org.example.payment.PaymentStatus;
import org.example.web.BusinessException;
import org.example.web.dto.AuthUserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class CustomerOrderLifecycleService {
    private final CustomerOrderMapper orderMapper;
    private final CustomerOrderItemMapper itemMapper;
    private final ProductMapper productMapper;
    private final InventoryLogMapper inventoryLogMapper;
    private final PaymentOrderMapper paymentOrderMapper;
    private final DistributedLockService lockService;
    private final TransactionTemplate lifecycleTransactionTemplate;

    public CustomerOrderLifecycleService(CustomerOrderMapper orderMapper,
                                         CustomerOrderItemMapper itemMapper,
                                         ProductMapper productMapper,
                                         InventoryLogMapper inventoryLogMapper,
                                         PaymentOrderMapper paymentOrderMapper,
                                         DistributedLockService lockService,
                                         TransactionTemplate transactionTemplate) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.productMapper = productMapper;
        this.inventoryLogMapper = inventoryLogMapper;
        this.paymentOrderMapper = paymentOrderMapper;
        this.lockService = lockService;
        this.lifecycleTransactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        this.lifecycleTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public CustomerOrderEntity cancel(AuthUserResponse user, long orderId) {
        return withOrderLock(orderId, () -> lifecycleTransactionTemplate.execute(status -> {
            CustomerOrderEntity order = requireVisibleOrder(user, orderId);
            if (!CustomerOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
                throw BusinessException.conflict("order cannot be canceled");
            }
            Instant now = Instant.now();
            if (hasUnexpiredOpenPayment(orderId, now)) {
                throw BusinessException.conflict("order has an active payment");
            }
            int updated = orderMapper.update(null, pendingTransition(orderId)
                    .set(CustomerOrderEntity::getStatus, CustomerOrderStatus.CANCELED.name())
                    .set(CustomerOrderEntity::getCanceledAt, now)
                    .set(CustomerOrderEntity::getUpdatedAt, now));
            if (updated != 1) {
                throw BusinessException.conflict("order cannot be canceled");
            }
            restoreInventory(orderId, "ORDER_CANCEL");
            order.setStatus(CustomerOrderStatus.CANCELED.name());
            order.setCanceledAt(now);
            order.setUpdatedAt(now);
            return order;
        }));
    }

    public void timeoutClose(long orderId) {
        withOrderLock(orderId, () -> lifecycleTransactionTemplate.execute(status -> {
            CustomerOrderEntity order = orderMapper.selectById(orderId);
            if (order == null || !CustomerOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
                return null;
            }
            Instant now = Instant.now();
            int updated = orderMapper.update(null, pendingTransition(orderId)
                    .set(CustomerOrderEntity::getStatus, CustomerOrderStatus.CANCELED.name())
                    .set(CustomerOrderEntity::getCanceledAt, now)
                    .set(CustomerOrderEntity::getUpdatedAt, now));
            if (updated == 1) {
                restoreInventory(orderId, "ORDER_TIMEOUT");
            }
            return null;
        }));
    }

    public void markPaid(long orderId) {
        withOrderLock(orderId, () -> lifecycleTransactionTemplate.execute(status -> {
            applySuccessfulPaymentLocked(orderId, Instant.now());
            return null;
        }));
    }

    public void applySuccessfulPaymentLocked(long orderId, Instant paidAt) {
        requireTransaction("customer order payment transition");
        CustomerOrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            return;
        }
        if (CustomerOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            int updated = orderMapper.update(null, pendingTransition(orderId)
                    .set(CustomerOrderEntity::getStatus, CustomerOrderStatus.PAID.name())
                    .set(CustomerOrderEntity::getPaidAt, paidAt)
                    .set(CustomerOrderEntity::getUpdatedAt, paidAt));
            requireSingleUpdate(updated, "customer order payment update conflicted");
        } else if (CustomerOrderStatus.CANCELED.name().equals(order.getStatus())) {
            int updated = orderMapper.update(null, new LambdaUpdateWrapper<CustomerOrderEntity>()
                    .eq(CustomerOrderEntity::getId, orderId)
                    .eq(CustomerOrderEntity::getStatus, CustomerOrderStatus.CANCELED.name())
                    .set(CustomerOrderEntity::getStatus, CustomerOrderStatus.REFUNDING.name())
                    .set(CustomerOrderEntity::getUpdatedAt, paidAt)
                    .setSql("version = version + 1"));
            requireSingleUpdate(updated, "customer order refund transition conflicted");
        }
    }

    public void commitShipmentInventoryLocked(long orderId) {
        requireTransaction("customer order shipment inventory transition");
        changeReservedInventory(orderId, "ORDER_SHIP", false);
    }

    public void releaseRefundedInventoryLocked(long orderId) {
        requireTransaction("customer order refund inventory transition");
        CustomerOrderEntity order = orderMapper.selectById(orderId);
        if (order == null || order.getCanceledAt() != null || order.getShippedAt() != null) {
            return;
        }
        changeReservedInventory(orderId, "ORDER_REFUND", true);
    }

    public <T> T withOrderLock(long orderId, Supplier<T> action) {
        return lockService.withLock("customer-order:id:" + orderId, action);
    }

    private CustomerOrderEntity requireVisibleOrder(AuthUserResponse user, long orderId) {
        CustomerOrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("customer order not found");
        }
        if (user.role() != UserRole.ADMIN && !order.getUserId().equals(user.id())) {
            throw BusinessException.forbidden("order belongs to another user");
        }
        return order;
    }

    private LambdaUpdateWrapper<CustomerOrderEntity> pendingTransition(long orderId) {
        return new LambdaUpdateWrapper<CustomerOrderEntity>()
                .eq(CustomerOrderEntity::getId, orderId)
                .eq(CustomerOrderEntity::getStatus, CustomerOrderStatus.PENDING_PAYMENT.name())
                .setSql("version = version + 1");
    }

    private boolean hasUnexpiredOpenPayment(long orderId, Instant now) {
        return paymentOrderMapper.selectCount(new LambdaQueryWrapper<PaymentOrderEntity>()
                .eq(PaymentOrderEntity::getOrderId, orderId)
                .in(PaymentOrderEntity::getStatus, PaymentStatus.PENDING.name(), PaymentStatus.PAYING.name())
                .gt(PaymentOrderEntity::getExpireAt, now)) > 0;
    }

    private void restoreInventory(long orderId, String reason) {
        changeReservedInventory(orderId, reason, true);
    }

    private void changeReservedInventory(long orderId, String reason, boolean release) {
        List<CustomerOrderItemEntity> items = itemMapper.selectList(
                new LambdaQueryWrapper<CustomerOrderItemEntity>()
                        .eq(CustomerOrderItemEntity::getOrderId, orderId));
        for (CustomerOrderItemEntity item : items) {
            int updated = release
                    ? productMapper.restoreStock(item.getProductId(), item.getQuantity())
                    : productMapper.commitStock(item.getProductId(), item.getQuantity());
            if (updated != 1) {
                throw BusinessException.conflict(release
                        ? "stock release conflicted"
                        : "stock commit conflicted");
            }
            InventoryLogEntity inventoryLog = new InventoryLogEntity();
            inventoryLog.setId(IdWorker.getId());
            inventoryLog.setProductId(item.getProductId());
            inventoryLog.setOrderId(orderId);
            inventoryLog.setQuantityChange(release ? item.getQuantity() : 0L);
            inventoryLog.setReservedChange(-item.getQuantity());
            inventoryLog.setSoldChange(release ? 0L : item.getQuantity());
            inventoryLog.setBusinessKey(reason + ":" + orderId + ":" + item.getProductId());
            inventoryLog.setReason(reason);
            inventoryLog.setCreatedAt(Instant.now());
            if (inventoryLogMapper.insert(inventoryLog) != 1) {
                throw BusinessException.conflict("inventory log insert conflicted");
            }
        }
    }

    private void requireSingleUpdate(int updated, String message) {
        if (updated != 1) {
            throw BusinessException.conflict(message);
        }
    }

    private void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " requires a transaction");
        }
    }
}

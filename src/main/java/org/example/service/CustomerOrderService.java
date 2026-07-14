package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.auth.UserRole;
import org.example.commerce.CustomerOrderStatus;
import org.example.config.AppProperties;
import org.example.infrastructure.mq.CustomerOrderTimeoutDispatcher;
import org.example.infrastructure.mybatis.entity.CustomerOrderEntity;
import org.example.infrastructure.mybatis.entity.CustomerOrderItemEntity;
import org.example.infrastructure.mybatis.entity.InventoryLogEntity;
import org.example.infrastructure.mybatis.entity.ProductEntity;
import org.example.infrastructure.mybatis.entity.ShippingAddressEntity;
import org.example.infrastructure.mybatis.mapper.CustomerOrderItemMapper;
import org.example.infrastructure.mybatis.mapper.CustomerOrderMapper;
import org.example.infrastructure.mybatis.mapper.InventoryLogMapper;
import org.example.infrastructure.mybatis.mapper.ProductMapper;
import org.example.payment.PaymentChannel;
import org.example.web.BusinessException;
import org.example.web.dto.AuthUserResponse;
import org.example.web.dto.CustomerOrderCreateRequest;
import org.example.web.dto.CustomerOrderItemRequest;
import org.example.web.dto.CustomerOrderItemResponse;
import org.example.web.dto.CustomerOrderResponse;
import org.example.web.dto.PaymentCreateRequest;
import org.example.web.dto.PaymentResponse;
import org.example.web.dto.RefundCreateRequest;
import org.example.web.dto.RefundResponse;
import org.example.web.dto.ShippingAddressSnapshot;
import org.example.web.dto.ShipmentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Service
public class CustomerOrderService {
    private static final Logger log = LoggerFactory.getLogger(CustomerOrderService.class);
    private final CustomerOrderMapper orderMapper;
    private final CustomerOrderItemMapper itemMapper;
    private final ProductMapper productMapper;
    private final InventoryLogMapper inventoryLogMapper;
    private final PaymentService paymentService;
    private final AddressService addressService;
    private final CartService cartService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate orderTransactionTemplate;
    private final CustomerOrderLifecycleService lifecycleService;
    private final CustomerOrderTimeoutDispatcher timeoutDispatcher;
    private final AppProperties properties;
    private final CustomerOrderOwnershipService ownershipService;

    public CustomerOrderService(CustomerOrderMapper orderMapper,
                                CustomerOrderItemMapper itemMapper,
                                ProductMapper productMapper,
                                InventoryLogMapper inventoryLogMapper,
                                PaymentService paymentService,
                                AddressService addressService,
                                CartService cartService,
                                ObjectMapper objectMapper,
                                TransactionTemplate transactionTemplate,
                                CustomerOrderLifecycleService lifecycleService,
                                CustomerOrderTimeoutDispatcher timeoutDispatcher,
                                AppProperties properties,
                                CustomerOrderOwnershipService ownershipService) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.productMapper = productMapper;
        this.inventoryLogMapper = inventoryLogMapper;
        this.paymentService = paymentService;
        this.addressService = addressService;
        this.cartService = cartService;
        this.objectMapper = objectMapper;
        this.lifecycleService = lifecycleService;
        this.timeoutDispatcher = timeoutDispatcher;
        this.properties = properties;
        this.ownershipService = ownershipService;
        this.orderTransactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        this.orderTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public CustomerOrderResponse create(AuthUserResponse user, CustomerOrderCreateRequest request) {
        List<Long> productIds = request.items().stream()
                .map(CustomerOrderItemRequest::productId)
                .toList();
        if (productIds.stream().distinct().count() != productIds.size()) {
            throw BusinessException.badRequest("duplicate product IDs");
        }
        return ownershipService.withUserLocks(List.of(user.id()), () ->
                cartService.withMutationLocks(user.id(), productIds, () ->
                        orderTransactionTemplate.execute(status -> createInTransaction(user, request, productIds))));
    }

    private CustomerOrderResponse createInTransaction(AuthUserResponse user,
                                                       CustomerOrderCreateRequest request,
                                                       List<Long> productIds) {
        ShippingAddressEntity address = addressService.requireOwned(user, request.addressId());
        ShippingAddressSnapshot shippingAddress = new ShippingAddressSnapshot(
                address.getReceiverName(), address.getReceiverPhone(), address.getProvince(), address.getCity(),
                address.getDistrict(), address.getDetail());
        long orderId = IdWorker.getId();
        Instant now = Instant.now();
        BigDecimal total = BigDecimal.ZERO;

        CustomerOrderEntity order = new CustomerOrderEntity();
        order.setId(orderId);
        order.setOrderNo("XW" + orderId);
        order.setUserId(user.id());
        order.setAddressId(request.addressId());
        order.setShippingSnapshot(serializeShippingAddress(shippingAddress));
        order.setStatus(CustomerOrderStatus.PENDING_PAYMENT.name());
        order.setRemark(request.remark());
        order.setVersion(0L);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);

        for (CustomerOrderItemRequest itemRequest : request.items()) {
            ProductEntity product = requireOnShelfProduct(itemRequest.productId());
            int deducted = productMapper.deductStock(product.getId(), itemRequest.quantity());
            if (deducted == 0) {
                throw BusinessException.conflict("insufficient stock");
            }
            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(itemRequest.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            total = total.add(subtotal);

            CustomerOrderItemEntity item = new CustomerOrderItemEntity();
            item.setId(IdWorker.getId());
            item.setOrderId(orderId);
            item.setProductId(product.getId());
            item.setSku(product.getSku());
            item.setProductName(product.getName());
            item.setUnitPrice(product.getPrice());
            item.setQuantity(itemRequest.quantity());
            item.setSubtotal(subtotal);
            itemMapper.insert(item);
            logInventory(product.getId(), orderId, -itemRequest.quantity(), "ORDER_CREATE");
        }
        order.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        orderMapper.insert(order);
        cartService.removePurchasedLocked(user.id(), productIds);
        timeoutDispatcher.dispatch(orderId, Duration.ofSeconds(properties.getOrder().getTimeoutSeconds()));
        log.info("customer order created orderId={} orderNo={} userId={} amount={}",
                order.getId(), order.getOrderNo(), user.id(), order.getTotalAmount());
        return toResponse(order);
    }

    public CustomerOrderResponse get(AuthUserResponse user, long orderId) {
        CustomerOrderEntity order = requireVisibleOrder(user, orderId);
        return toResponse(order);
    }

    public List<CustomerOrderResponse> list(AuthUserResponse user) {
        LambdaQueryWrapper<CustomerOrderEntity> query = new LambdaQueryWrapper<CustomerOrderEntity>()
                .orderByDesc(CustomerOrderEntity::getCreatedAt);
        if (user.role() != UserRole.ADMIN) {
            query.eq(CustomerOrderEntity::getUserId, user.id());
        }
        return orderMapper.selectList(query).stream().map(this::toResponse).toList();
    }

    public CustomerOrderResponse cancel(AuthUserResponse user, long orderId) {
        CustomerOrderEntity order = lifecycleService.cancel(user, orderId);
        log.info("customer order canceled orderId={} userId={}", orderId, user.id());
        return toResponse(order);
    }

    public PaymentResponse createPayment(AuthUserResponse user, long orderId, PaymentChannel channel) {
        return lifecycleService.withOrderLock(orderId, () -> orderTransactionTemplate.execute(status -> {
            CustomerOrderEntity order = requireVisibleOrder(user, orderId);
            if (!CustomerOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
                throw BusinessException.conflict("order is not payable");
            }
            return paymentService.createForCustomerOrder(
                    new PaymentCreateRequest(orderId, channel), order.getTotalAmount());
        }));
    }

    public RefundResponse refund(AuthUserResponse user, long orderId, RefundCreateRequest request) {
        RefundResponse refund = paymentService.refundCustomerOrder(user, orderId, request);
        log.info("customer order refunded orderId={} refundId={} status={}", orderId, refund.id(), refund.status());
        return refund;
    }

    public void markPaid(long orderId) {
        lifecycleService.markPaid(orderId);
    }

    public void timeoutCloseCustomerOrder(long orderId) {
        lifecycleService.timeoutClose(orderId);
    }

    public CustomerOrderResponse ship(AuthUserResponse admin, long orderId, ShipmentRequest request) {
        if (admin.role() != UserRole.ADMIN) {
            throw BusinessException.forbidden("admin role required");
        }
        return lifecycleService.withOrderLock(orderId, () -> orderTransactionTemplate.execute(status -> {
            CustomerOrderEntity order = orderMapper.selectById(orderId);
            if (order == null) {
                throw BusinessException.notFound("customer order not found");
            }
            if (!CustomerOrderStatus.PAID.name().equals(order.getStatus())
                    && !CustomerOrderStatus.FULFILLING.name().equals(order.getStatus())) {
                throw BusinessException.conflict("order is not shippable");
            }
            Instant now = Instant.now();
            order.setStatus(CustomerOrderStatus.SHIPPED.name());
            order.setShippingCarrier(request.carrier());
            order.setTrackingNo(request.trackingNo());
            order.setShippedAt(now);
            order.setUpdatedAt(now);
            updateOrder(order);
            log.info("customer order shipped orderId={} adminId={} carrier={} trackingNo={}",
                    orderId, admin.id(), request.carrier(), request.trackingNo());
            return toResponse(order);
        }));
    }

    private ProductEntity requireOnShelfProduct(long productId) {
        ProductEntity product = productMapper.selectById(productId);
        if (product == null) {
            throw BusinessException.notFound("product not found");
        }
        if (!"ON_SHELF".equals(product.getStatus())) {
            throw BusinessException.conflict("product is not on shelf");
        }
        return product;
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

    private void updateOrder(CustomerOrderEntity order) {
        if (orderMapper.updateById(order) == 0) {
            throw BusinessException.conflict("customer order update conflicted");
        }
    }

    private void logInventory(long productId, long orderId, long quantityChange, String reason) {
        InventoryLogEntity log = new InventoryLogEntity();
        log.setId(IdWorker.getId());
        log.setProductId(productId);
        log.setOrderId(orderId);
        log.setQuantityChange(quantityChange);
        log.setReason(reason);
        log.setCreatedAt(Instant.now());
        inventoryLogMapper.insert(log);
    }

    private List<CustomerOrderItemEntity> items(long orderId) {
        return itemMapper.selectList(new LambdaQueryWrapper<CustomerOrderItemEntity>()
                .eq(CustomerOrderItemEntity::getOrderId, orderId));
    }

    private CustomerOrderResponse toResponse(CustomerOrderEntity order) {
        List<CustomerOrderItemResponse> itemResponses = items(order.getId()).stream()
                .map(item -> new CustomerOrderItemResponse(item.getId(), item.getProductId(), item.getSku(), item.getProductName(),
                        item.getUnitPrice(), item.getQuantity(), item.getSubtotal()))
                .toList();
        return new CustomerOrderResponse(order.getId(), order.getOrderNo(), order.getUserId(), order.getAddressId(),
                deserializeShippingAddress(order),
                order.getTotalAmount(), CustomerOrderStatus.valueOf(order.getStatus()), order.getRemark(), itemResponses,
                order.getCreatedAt(), order.getUpdatedAt(), order.getPaidAt(), order.getShippedAt(), order.getCanceledAt(),
                order.getShippingCarrier(), order.getTrackingNo());
    }

    private String serializeShippingAddress(ShippingAddressSnapshot shippingAddress) {
        try {
            return objectMapper.writeValueAsString(shippingAddress);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize shipping address snapshot", exception);
        }
    }

    private ShippingAddressSnapshot deserializeShippingAddress(CustomerOrderEntity order) {
        if (order.getShippingSnapshot() == null || order.getShippingSnapshot().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(order.getShippingSnapshot(), ShippingAddressSnapshot.class);
        } catch (JsonProcessingException exception) {
            log.warn("invalid shipping address snapshot orderId={} error={}",
                    order.getId(), exception.getOriginalMessage());
            return null;
        }
    }
}

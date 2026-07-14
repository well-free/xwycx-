package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.auth.UserRole;
import org.example.commerce.CustomerOrderStatus;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mybatis.entity.InventoryLogEntity;
import org.example.infrastructure.mybatis.mapper.CustomerOrderMapper;
import org.example.infrastructure.mybatis.mapper.InventoryLogMapper;
import org.example.infrastructure.mybatis.mapper.PaymentCallbackMapper;
import org.example.infrastructure.mybatis.mapper.PaymentOrderMapper;
import org.example.infrastructure.mybatis.mapper.ProductMapper;
import org.example.infrastructure.mybatis.mapper.RefundOrderMapper;
import org.example.payment.PaymentChannel;
import org.example.payment.PaymentGateway;
import org.example.payment.PaymentGatewayResult;
import org.example.payment.PaymentRefundResult;
import org.example.payment.PaymentStatus;
import org.example.refund.RefundStatus;
import org.example.web.BusinessException;
import org.example.web.dto.AuthUserResponse;
import org.example.web.dto.CustomerOrderCreateRequest;
import org.example.web.dto.CustomerOrderItemRequest;
import org.example.web.dto.PaymentCallbackRequest;
import org.example.web.dto.PaymentCreateRequest;
import org.example.web.dto.ShipmentRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false",
        "app.order.timeout-seconds=3600",
        "spring.datasource.url=jdbc:h2:mem:customer_order_lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
})
class CustomerOrderLifecycleTest {
    private static final AuthUserResponse USER =
            new AuthUserResponse(701L, "13800000701", UserRole.CUSTOMER);
    private static final AuthUserResponse ADMIN =
            new AuthUserResponse(1L, "13900000000", UserRole.ADMIN);
    private static final long ADDRESS_ID = 9701L;
    private static final long PRODUCT_ONE_ID = 9201L;
    private static final long PRODUCT_TWO_ID = 9202L;

    @Autowired
    private CustomerOrderService customerOrderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private CustomerOrderMapper orderMapper;

    @Autowired
    private InventoryLogMapper inventoryLogMapper;

    @Autowired
    private PaymentOrderMapper paymentMapper;

    @Autowired
    private RefundOrderMapper refundMapper;

    @Autowired
    private PaymentCompensationService compensationService;

    @Autowired
    private PaymentCallbackMapper callbackMapper;

    @SpyBean
    private ProductMapper productMapper;

    @SpyBean
    private DistributedLockService lockService;

    @SpyBean
    private PaymentGateway paymentGateway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        insertProduct(PRODUCT_ONE_ID, "LIFECYCLE-ONE", 5L);
        insertProduct(PRODUCT_TWO_ID, "LIFECYCLE-TWO", 5L);
        jdbcTemplate.update("""
                        insert into shipping_addresses
                          (id, user_id, receiver_name, receiver_phone, province, city, district, detail,
                           default_address, created_at, updated_at)
                        values (?, ?, 'Alice', '13800000701', 'Zhejiang', 'Hangzhou', 'Xihu', 'No. 1',
                                true, current_timestamp, current_timestamp)
                        """, ADDRESS_ID, USER.id());
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    @Test
    void cancelThenSuccessfulCallbackCompensatesWithoutSecondStockRestore() {
        var order = createOrder(PRODUCT_ONE_ID);
        var payment = createPayment(order.id(), order.totalAmount());
        expirePayment(payment.id());

        customerOrderService.cancel(USER, order.id());
        clearInvocations(lockService);
        paymentService.handleCallback(PaymentChannel.ALIPAY, success(payment.id(), payment.amount(), "cancel-first"));

        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(CustomerOrderStatus.REFUNDED.name());
        assertThat(paymentMapper.selectById(payment.id()).getStatus()).isEqualTo(PaymentStatus.REFUNDED.name());
        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(5L);
        assertThat(restorationLogCount(order.id())).isEqualTo(1L);
        var locks = inOrder(lockService);
        locks.verify(lockService).withLock(eq("payment:id:" + payment.id()), any());
        locks.verify(lockService).withLock(eq("customer-order:id:" + order.id()), any());
    }

    @Test
    void compensationRefundFailureRetriesDurablyAndRemainsIdempotent() {
        var order = createOrder(PRODUCT_ONE_ID);
        var payment = createPayment(order.id(), order.totalAmount());
        expirePayment(payment.id());
        customerOrderService.cancel(USER, order.id());
        AtomicInteger gatewayCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (gatewayCalls.incrementAndGet() == 1) {
                throw BusinessException.serviceUnavailable("temporary refund failure");
            }
            long refundId = invocation.getArgument(1);
            return new PaymentRefundResult("retry-refund-" + refundId, RefundStatus.SUCCESS);
        }).when(paymentGateway).refund(eq(PaymentChannel.ALIPAY), anyLong(), eq(payment.id()),
                eq(payment.amount()), eq(payment.amount()), any());

        paymentService.handleCallback(PaymentChannel.ALIPAY,
                success(payment.id(), payment.amount(), "compensation-retry"));

        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(CustomerOrderStatus.REFUNDING.name());
        assertThat(paymentMapper.selectById(payment.id()).getStatus()).isEqualTo(PaymentStatus.SUCCESS.name());
        assertThat(refundMapper.selectList(null)).singleElement().satisfies(refund -> {
            assertThat(refund.getPaymentId()).isEqualTo(payment.id());
            assertThat(refund.getStatus()).isEqualTo(RefundStatus.PROCESSING.name());
        });

        compensationService.retryRefundingOrders();

        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(CustomerOrderStatus.REFUNDED.name());
        assertThat(paymentMapper.selectById(payment.id()).getStatus()).isEqualTo(PaymentStatus.REFUNDED.name());
        assertThat(refundMapper.selectList(null)).singleElement().satisfies(refund -> {
            assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCESS.name());
            assertThat(refund.getChannelRefundNo()).isEqualTo("retry-refund-" + refund.getId());
            assertThat(refund.getCompletedAt()).isNotNull();
        });

        compensationService.retryRefundingOrders();

        assertThat(gatewayCalls).hasValue(2);
        assertThat(refundMapper.selectCount(null)).isEqualTo(1L);
        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(5L);
        assertThat(restorationLogCount(order.id())).isEqualTo(1L);
    }

    @Test
    void cancellationCommitsBeforeCustomerOrderLockActionReturns() {
        var order = createOrder(PRODUCT_ONE_ID);
        AtomicBoolean committedBeforeRelease = new AtomicBoolean();
        doAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            Object response = action.get();
            committedBeforeRelease.set(
                    !TransactionSynchronizationManager.isActualTransactionActive()
                            && CustomerOrderStatus.CANCELED.name().equals(
                            orderMapper.selectById(order.id()).getStatus()));
            return response;
        }).when(lockService).withLock(eq("customer-order:id:" + order.id()), any());

        customerOrderService.cancel(USER, order.id());

        assertThat(committedBeforeRelease).isTrue();
    }

    @Test
    void cancellationRejectsUnexpiredOpenCustomerPayment() {
        var order = createOrder(PRODUCT_ONE_ID);
        var payment = customerOrderService.createPayment(USER, order.id(), PaymentChannel.ALIPAY);

        assertThatThrownBy(() -> customerOrderService.cancel(USER, order.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("order has an active payment");

        assertThat(orderMapper.selectById(order.id()).getStatus())
                .isEqualTo(CustomerOrderStatus.PENDING_PAYMENT.name());
        assertThat(paymentMapper.selectById(payment.id()).getStatus()).isEqualTo(PaymentStatus.PAYING.name());
        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(4L);
        assertThat(restorationLogCount(order.id())).isZero();
    }

    @Test
    void customerOrderReusesActivePaymentAcrossChannels() {
        var order = createOrder(PRODUCT_ONE_ID);

        var alipay = customerOrderService.createPayment(USER, order.id(), PaymentChannel.ALIPAY);
        var wechat = customerOrderService.createPayment(USER, order.id(), PaymentChannel.WECHAT);

        assertThat(wechat.id()).isEqualTo(alipay.id());
        assertThat(wechat.channel()).isEqualTo(PaymentChannel.ALIPAY);
        assertThat(paymentMapper.selectList(new LambdaQueryWrapper<org.example.infrastructure.mybatis.entity.PaymentOrderEntity>()
                .eq(org.example.infrastructure.mybatis.entity.PaymentOrderEntity::getOrderId, order.id())))
                .singleElement();
    }

    @Test
    void refundedPaymentIgnoresLaterSuccessCallbackWithoutResurrectingOrder() {
        var order = createOrder(PRODUCT_ONE_ID);
        var payment = createPayment(order.id(), order.totalAmount());
        paymentService.handleCallback(PaymentChannel.ALIPAY,
                success(payment.id(), payment.amount(), "initial-payment"));
        customerOrderService.refund(USER, order.id(),
                new org.example.web.dto.RefundCreateRequest(payment.amount(), "customer refund"));

        paymentService.handleCallback(PaymentChannel.ALIPAY,
                success(payment.id(), payment.amount(), "late-duplicate-success"));

        assertThat(paymentMapper.selectById(payment.id()).getStatus()).isEqualTo(PaymentStatus.REFUNDED.name());
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(CustomerOrderStatus.REFUNDED.name());
        assertThat(callbackMapper.selectCount(new LambdaQueryWrapper<org.example.infrastructure.mybatis.entity.PaymentCallbackEntity>()
                .eq(org.example.infrastructure.mybatis.entity.PaymentCallbackEntity::getPaymentId, payment.id())))
                .isEqualTo(2L);
    }

    @Test
    void processingCustomerRefundKeepsOrderRefunding() {
        var order = createOrder(PRODUCT_ONE_ID);
        var payment = createPayment(order.id(), order.totalAmount());
        paymentService.handleCallback(PaymentChannel.ALIPAY,
                success(payment.id(), payment.amount(), "paid-before-processing-refund"));
        doReturn(new PaymentRefundResult("processing-refund", RefundStatus.PROCESSING))
                .when(paymentGateway).refund(eq(PaymentChannel.ALIPAY), anyLong(), eq(payment.id()),
                        eq(payment.amount()), eq(payment.amount()), any());

        var refund = customerOrderService.refund(USER, order.id(),
                new org.example.web.dto.RefundCreateRequest(payment.amount(), "processing refund"));

        assertThat(refund.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(paymentMapper.selectById(payment.id()).getStatus()).isEqualTo(PaymentStatus.REFUNDING.name());
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(CustomerOrderStatus.REFUNDING.name());
    }

    @Test
    void partialRefundRetriesExactAmountAndOnlyCompletesOrderAfterRemainingRefund() {
        var order = createOrder(PRODUCT_ONE_ID);
        var payment = createPayment(order.id(), order.totalAmount());
        paymentService.handleCallback(PaymentChannel.ALIPAY,
                success(payment.id(), payment.amount(), "paid-before-partial-refund"));
        doReturn(
                new PaymentRefundResult("partial-processing", RefundStatus.PROCESSING),
                new PaymentRefundResult("partial-success", RefundStatus.SUCCESS))
                .when(paymentGateway).refund(
                        eq(PaymentChannel.ALIPAY), anyLong(), eq(payment.id()),
                        eq(new BigDecimal("4.00")), eq(payment.amount()), any());

        customerOrderService.refund(USER, order.id(),
                new org.example.web.dto.RefundCreateRequest(new BigDecimal("4.00"), "partial refund"));

        assertThat(paymentMapper.selectById(payment.id()).getStatus()).isEqualTo(PaymentStatus.REFUNDING.name());
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(CustomerOrderStatus.PAID.name());

        compensationService.retryRefundingOrders();

        assertThat(paymentMapper.selectById(payment.id()).getStatus()).isEqualTo(PaymentStatus.SUCCESS.name());
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(CustomerOrderStatus.PAID.name());

        customerOrderService.refund(USER, order.id(),
                new org.example.web.dto.RefundCreateRequest(new BigDecimal("6.00"), "remaining refund"));

        assertThat(paymentMapper.selectById(payment.id()).getStatus()).isEqualTo(PaymentStatus.REFUNDED.name());
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(CustomerOrderStatus.REFUNDED.name());
    }

    @Test
    void legacySecondSuccessfulPaymentIsRefundedWithoutChangingPaidOrder() {
        var order = createOrder(PRODUCT_ONE_ID);
        var original = createPayment(order.id(), order.totalAmount());
        paymentService.handleCallback(PaymentChannel.ALIPAY,
                success(original.id(), original.amount(), "original-success"));
        long duplicatePaymentId = 9799L;
        insertPayment(duplicatePaymentId, order.id(), PaymentChannel.WECHAT, order.totalAmount());

        paymentService.handleCallback(PaymentChannel.WECHAT,
                success(duplicatePaymentId, order.totalAmount(), "legacy-duplicate-success"));

        assertThat(paymentMapper.selectById(original.id()).getStatus()).isEqualTo(PaymentStatus.SUCCESS.name());
        assertThat(paymentMapper.selectById(duplicatePaymentId).getStatus()).isEqualTo(PaymentStatus.REFUNDED.name());
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(CustomerOrderStatus.PAID.name());
        assertThat(refundMapper.selectList(new LambdaQueryWrapper<org.example.infrastructure.mybatis.entity.RefundOrderEntity>()
                .eq(org.example.infrastructure.mybatis.entity.RefundOrderEntity::getPaymentId, duplicatePaymentId)))
                .singleElement()
                .satisfies(refund -> assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCESS.name()));
    }

    @Test
    void createPaymentAndCancelCannotProduceCanceledOrderWithOpenPayment() throws Exception {
        var order = createOrder(PRODUCT_ONE_ID);
        CountDownLatch gatewayEntered = new CountDownLatch(1);
        CountDownLatch releaseGateway = new CountDownLatch(1);
        CountDownLatch cancelStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            gatewayEntered.countDown();
            await(releaseGateway);
            return new PaymentGatewayResult("pay-url", "qr-code");
        }).when(paymentGateway).createPayment(eq(PaymentChannel.ALIPAY), any(Long.class), any(BigDecimal.class));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> payment = executor.submit(() ->
                    customerOrderService.createPayment(USER, order.id(), PaymentChannel.ALIPAY));
            await(gatewayEntered);
            Future<Throwable> cancellation = executor.submit(() -> {
                cancelStarted.countDown();
                try {
                    customerOrderService.cancel(USER, order.id());
                    return null;
                } catch (Throwable exception) {
                    return exception;
                }
            });
            await(cancelStarted);
            Thread.sleep(100);
            releaseGateway.countDown();

            payment.get(5, TimeUnit.SECONDS);
            assertThat(cancellation.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("order has an active payment");
        } finally {
            releaseGateway.countDown();
        }

        assertThat(orderMapper.selectById(order.id()).getStatus())
                .isEqualTo(CustomerOrderStatus.PENDING_PAYMENT.name());
        assertThat(paymentMapper.selectCount(new LambdaQueryWrapper<org.example.infrastructure.mybatis.entity.PaymentOrderEntity>()
                .eq(org.example.infrastructure.mybatis.entity.PaymentOrderEntity::getOrderId, order.id())
                .in(org.example.infrastructure.mybatis.entity.PaymentOrderEntity::getStatus,
                        PaymentStatus.PENDING.name(), PaymentStatus.PAYING.name())))
                .isEqualTo(1L);
        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(4L);
        assertThat(restorationLogCount(order.id())).isZero();
    }

    @Test
    void successfulCallbackThenCancelLeavesPaidOrderAndDeductedStock() {
        var order = createOrder(PRODUCT_ONE_ID);
        var payment = createPayment(order.id(), order.totalAmount());

        paymentService.handleCallback(PaymentChannel.ALIPAY, success(payment.id(), payment.amount(), "payment-first"));

        assertThatThrownBy(() -> customerOrderService.cancel(USER, order.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("order cannot be canceled");
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(CustomerOrderStatus.PAID.name());
        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(4L);
        assertThat(restorationLogCount(order.id())).isZero();
    }

    @Test
    void concurrentCancelAndCallbackNeverLeaveCanceledOrderWithSuccessfulPayment() throws Exception {
        var order = createOrder(PRODUCT_ONE_ID);
        var payment = createPayment(order.id(), order.totalAmount());
        expirePayment(payment.id());
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> cancel = executor.submit(() -> {
                await(start);
                try {
                    customerOrderService.cancel(USER, order.id());
                } catch (BusinessException ignored) {
                    // Payment may win the customer-order lock.
                }
            });
            Future<?> callback = executor.submit(() -> {
                await(start);
                paymentService.handleCallback(PaymentChannel.ALIPAY,
                        success(payment.id(), payment.amount(), "concurrent"));
            });
            start.countDown();
            cancel.get(5, TimeUnit.SECONDS);
            callback.get(5, TimeUnit.SECONDS);
        }

        String status = orderMapper.selectById(order.id()).getStatus();
        assertThat(status).isIn(CustomerOrderStatus.PAID.name(), CustomerOrderStatus.REFUNDED.name());
        if (CustomerOrderStatus.PAID.name().equals(status)) {
            assertThat(paymentMapper.selectById(payment.id()).getStatus()).isEqualTo(PaymentStatus.SUCCESS.name());
            assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(4L);
            assertThat(restorationLogCount(order.id())).isZero();
        } else {
            assertThat(paymentMapper.selectById(payment.id()).getStatus()).isEqualTo(PaymentStatus.REFUNDED.name());
            assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(5L);
            assertThat(restorationLogCount(order.id())).isEqualTo(1L);
        }
    }

    @Test
    void restorationFailureRollsBackStatusStockAndAllCancellationLogs() {
        var order = createOrder(PRODUCT_ONE_ID, PRODUCT_TWO_ID);
        doReturn(0).when(productMapper).restoreStock(PRODUCT_TWO_ID, 1L);

        assertThatThrownBy(() -> customerOrderService.cancel(USER, order.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("stock restore conflicted");

        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(CustomerOrderStatus.PENDING_PAYMENT.name());
        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(4L);
        assertThat(stock(PRODUCT_TWO_ID)).isEqualTo(4L);
        assertThat(restorationLogCount(order.id())).isZero();
    }

    @Test
    void duplicateTimeoutAndCancellationRestoreAndLogOnlyOnce() {
        var timedOut = createOrder(PRODUCT_ONE_ID);

        customerOrderService.timeoutCloseCustomerOrder(timedOut.id());
        customerOrderService.timeoutCloseCustomerOrder(timedOut.id());

        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(5L);
        assertThat(inventoryLogMapper.selectCount(new LambdaQueryWrapper<InventoryLogEntity>()
                .eq(InventoryLogEntity::getOrderId, timedOut.id())
                .eq(InventoryLogEntity::getReason, "ORDER_TIMEOUT"))).isEqualTo(1L);

        var canceled = createOrder(PRODUCT_TWO_ID);
        customerOrderService.cancel(USER, canceled.id());
        assertThatThrownBy(() -> customerOrderService.cancel(USER, canceled.id()))
                .isInstanceOf(BusinessException.class);
        customerOrderService.timeoutCloseCustomerOrder(canceled.id());

        assertThat(stock(PRODUCT_TWO_ID)).isEqualTo(5L);
        assertThat(inventoryLogMapper.selectCount(new LambdaQueryWrapper<InventoryLogEntity>()
                .eq(InventoryLogEntity::getOrderId, canceled.id())
                .in(InventoryLogEntity::getReason, "ORDER_CANCEL", "ORDER_TIMEOUT"))).isEqualTo(1L);
    }

    @Test
    void timeoutIgnoresPaidOrder() {
        var order = createOrder(PRODUCT_ONE_ID);
        var payment = createPayment(order.id(), order.totalAmount());
        paymentService.handleCallback(PaymentChannel.ALIPAY, success(payment.id(), payment.amount(), "paid-timeout"));

        customerOrderService.timeoutCloseCustomerOrder(order.id());

        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(CustomerOrderStatus.PAID.name());
        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(4L);
        assertThat(restorationLogCount(order.id())).isZero();
    }

    @Test
    void shipmentPersistsCarrierAndTrackingNumber() {
        var order = createOrder(PRODUCT_ONE_ID);
        var payment = createPayment(order.id(), order.totalAmount());
        paymentService.handleCallback(PaymentChannel.ALIPAY,
                success(payment.id(), payment.amount(), "paid-before-shipment"));

        var shipped = customerOrderService.ship(
                ADMIN, order.id(), new ShipmentRequest("SF Express", "SF123456789"));

        assertThat(shipped.status()).isEqualTo(CustomerOrderStatus.SHIPPED);
        assertThat(shipped.shippingCarrier()).isEqualTo("SF Express");
        assertThat(shipped.trackingNo()).isEqualTo("SF123456789");
        assertThat(orderMapper.selectById(order.id()).getTrackingNo()).isEqualTo("SF123456789");
    }

    private org.example.web.dto.CustomerOrderResponse createOrder(long... productIds) {
        List<CustomerOrderItemRequest> items = java.util.Arrays.stream(productIds)
                .mapToObj(id -> new CustomerOrderItemRequest(id, 1L))
                .toList();
        return customerOrderService.create(USER, new CustomerOrderCreateRequest(items, ADDRESS_ID, null));
    }

    private org.example.web.dto.PaymentResponse createPayment(long orderId, BigDecimal amount) {
        return customerOrderService.createPayment(USER, orderId, PaymentChannel.ALIPAY);
    }

    private PaymentCallbackRequest success(long paymentId, BigDecimal amount, String notifyId) {
        return new PaymentCallbackRequest(paymentId, notifyId, "trade-" + notifyId,
                amount, "SUCCESS", "mock-signature");
    }

    private long stock(long productId) {
        return productMapper.selectById(productId).getStock();
    }

    private long restorationLogCount(long orderId) {
        return inventoryLogMapper.selectCount(new LambdaQueryWrapper<InventoryLogEntity>()
                .eq(InventoryLogEntity::getOrderId, orderId)
                .gt(InventoryLogEntity::getQuantityChange, 0));
    }

    private void expirePayment(long paymentId) {
        jdbcTemplate.update("update payment_orders set expire_at=? where id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), paymentId);
    }

    private void insertProduct(long id, String sku, long stock) {
        jdbcTemplate.update("""
                        insert into products
                          (id, merchant_id, sku, name, price, stock, hot_score, main_image, detail_images,
                           spec, unit, status, sort_order, updated_at)
                        values (?, 1, ?, ?, 10.00, ?, 0, '', '', '', 'piece', 'ON_SHELF', 0, current_timestamp)
                 """, id, sku, sku, stock);
    }

    private void insertPayment(long paymentId,
                               long orderId,
                               PaymentChannel channel,
                               BigDecimal amount) {
        jdbcTemplate.update("""
                        insert into payment_orders
                          (id, order_id, gateway_mode, channel, amount, status, version,
                           created_at, updated_at, expire_at)
                        values (?, ?, 'mock', ?, ?, 'PAYING', 0,
                                current_timestamp, current_timestamp, ?)
                        """,
                paymentId, orderId, channel.name(), amount,
                Timestamp.from(Instant.now().plusSeconds(300)));
    }

    private void cleanFixtures() {
        List<Long> orderIds = jdbcTemplate.queryForList(
                "select id from customer_orders where user_id=?", Long.class, USER.id());
        for (Long orderId : orderIds) {
            List<Long> paymentIds = jdbcTemplate.queryForList(
                    "select id from payment_orders where order_id=?", Long.class, orderId);
            for (Long paymentId : paymentIds) {
                jdbcTemplate.update("delete from refund_orders where payment_id=?", paymentId);
                jdbcTemplate.update("delete from payment_callbacks where payment_id=?", paymentId);
            }
            jdbcTemplate.update("delete from payment_orders where order_id=?", orderId);
            jdbcTemplate.update("delete from inventory_logs where order_id=?", orderId);
            jdbcTemplate.update("delete from customer_order_items where order_id=?", orderId);
        }
        jdbcTemplate.update("delete from customer_orders where user_id=?", USER.id());
        jdbcTemplate.update("delete from cart_items where user_id=?", USER.id());
        jdbcTemplate.update("delete from shipping_addresses where id=?", ADDRESS_ID);
        jdbcTemplate.update("delete from products where id in (?, ?)", PRODUCT_ONE_ID, PRODUCT_TWO_ID);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}

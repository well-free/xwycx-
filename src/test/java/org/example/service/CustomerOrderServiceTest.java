package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.auth.UserRole;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mq.CustomerOrderTimeoutDispatcher;
import org.example.infrastructure.mybatis.entity.CustomerOrderEntity;
import org.example.infrastructure.mybatis.entity.InventoryLogEntity;
import org.example.infrastructure.mybatis.entity.ProductEntity;
import org.example.infrastructure.mybatis.mapper.CustomerOrderItemMapper;
import org.example.infrastructure.mybatis.mapper.CustomerOrderMapper;
import org.example.infrastructure.mybatis.mapper.InventoryLogMapper;
import org.example.infrastructure.mybatis.mapper.ProductMapper;
import org.example.web.BusinessException;
import org.example.web.dto.AuthUserResponse;
import org.example.web.dto.CartItemRequest;
import org.example.web.dto.CustomerOrderCreateRequest;
import org.example.web.dto.CustomerOrderItemRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false"
})
class CustomerOrderServiceTest {
    private static final AuthUserResponse USER =
            new AuthUserResponse(501L, "13800000501", UserRole.CUSTOMER);
    private static final AuthUserResponse OTHER_USER =
            new AuthUserResponse(502L, "13800000502", UserRole.CUSTOMER);
    private static final long ADDRESS_ID = 9501L;
    private static final long OTHER_ADDRESS_ID = 9502L;
    private static final long PRODUCT_ONE_ID = 9101L;
    private static final long PRODUCT_TWO_ID = 9102L;

    @Autowired
    private CustomerOrderService customerOrderService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CustomerOrderMapper orderMapper;

    @SpyBean
    private CustomerOrderItemMapper itemMapper;

    @Autowired
    private InventoryLogMapper inventoryLogMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private DistributedLockService lockService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private CustomerOrderTimeoutDispatcher timeoutDispatcher;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        insertProduct(PRODUCT_ONE_ID, "TASK5-ONE", "Task5 One", "10.00", 5, "ON_SHELF");
        insertProduct(PRODUCT_TWO_ID, "TASK5-TWO", "Task5 Two", "7.50", 5, "ON_SHELF");
        insertAddress(ADDRESS_ID, USER.id(), "Alice", "13800000501",
                "Zhejiang", "Hangzhou", "Xihu", "No. 1 Road");
        insertAddress(OTHER_ADDRESS_ID, OTHER_USER.id(), "Bob", "13800000502",
                "Jiangsu", "Nanjing", "Gulou", "No. 2 Road");
        clearInvocations(lockService);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    @Test
    void createUsesCurrentPriceKeepsAddressSnapshotAndCleansOnlyPurchasedCartAfterCommit() {
        cartService.put(USER, new CartItemRequest(PRODUCT_ONE_ID, 2L));
        cartService.put(USER, new CartItemRequest(PRODUCT_TWO_ID, 1L));
        jdbcTemplate.update("update products set price=12.34 where id=?", PRODUCT_ONE_ID);
        clearInvocations(lockService);
        AtomicBoolean committedBeforeOrderLockRelease = new AtomicBoolean();
        doAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            Object response = action.get();
            Long persistedOrders = jdbcTemplate.queryForObject(
                    "select count(*) from customer_orders where user_id=?", Long.class, USER.id());
            committedBeforeOrderLockRelease.set(
                    !TransactionSynchronizationManager.isActualTransactionActive() && persistedOrders == 1L);
            return response;
        }).when(lockService).withLock(eq("customer-order:user:" + USER.id()), any());

        var created = customerOrderService.create(USER,
                request(new CustomerOrderItemRequest(PRODUCT_ONE_ID, 2L)));

        assertThat(created.addressId()).isEqualTo(ADDRESS_ID);
        assertThat(created.shippingAddress().receiverName()).isEqualTo("Alice");
        assertThat(created.shippingAddress().receiverPhone()).isEqualTo("13800000501");
        assertThat(created.shippingAddress().province()).isEqualTo("Zhejiang");
        assertThat(created.shippingAddress().city()).isEqualTo("Hangzhou");
        assertThat(created.shippingAddress().district()).isEqualTo("Xihu");
        assertThat(created.shippingAddress().detail()).isEqualTo("No. 1 Road");
        assertThat(created.totalAmount()).isEqualByComparingTo("24.68");
        assertThat(created.items()).singleElement().satisfies(item -> {
            assertThat(item.sku()).isEqualTo("TASK5-ONE");
            assertThat(item.productName()).isEqualTo("Task5 One");
            assertThat(item.unitPrice()).isEqualByComparingTo("12.34");
            assertThat(item.subtotal()).isEqualByComparingTo("24.68");
        });
        assertThat(orderMapper.selectById(created.id()).getShippingSnapshot()).contains("Alice", "No. 1 Road");
        assertThat(cartService.list(USER)).extracting(item -> item.productId())
                .containsExactly(PRODUCT_TWO_ID);
        assertThat(committedBeforeOrderLockRelease).isTrue();
        verify(timeoutDispatcher).dispatch(created.id(), Duration.ofSeconds(15));

        jdbcTemplate.update("update shipping_addresses set receiver_name='Changed', detail='Changed Road' where id=?",
                ADDRESS_ID);

        var historical = customerOrderService.get(USER, created.id());
        assertThat(historical.shippingAddress().receiverName()).isEqualTo("Alice");
        assertThat(historical.shippingAddress().detail()).isEqualTo("No. 1 Road");
    }

    @Test
    void duplicateProductIdsAreRejectedBeforeLockOrMutation() {
        long originalStock = stock(PRODUCT_ONE_ID);

        assertBusinessError(
                () -> customerOrderService.create(USER, new CustomerOrderCreateRequest(
                        List.of(new CustomerOrderItemRequest(PRODUCT_ONE_ID, 1L),
                                new CustomerOrderItemRequest(PRODUCT_ONE_ID, 2L)),
                        ADDRESS_ID, null)),
                HttpStatus.BAD_REQUEST, "duplicate product IDs");

        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(originalStock);
        assertThat(orderCount()).isZero();
        assertThat(itemMapper.selectCount(null)).isZero();
        assertThat(inventoryLogMapper.selectCount(null)).isZero();
        verify(lockService, never()).withLock(eq("customer-order:user:" + USER.id()), any());
    }

    @Test
    void createRequiresOwnedAddressBeforeInventoryMutation() {
        long originalStock = stock(PRODUCT_ONE_ID);

        assertBusinessError(
                () -> customerOrderService.create(USER, new CustomerOrderCreateRequest(
                        List.of(new CustomerOrderItemRequest(PRODUCT_ONE_ID, 1L)),
                        OTHER_ADDRESS_ID, null)),
                HttpStatus.NOT_FOUND, "address not found");

        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(originalStock);
        assertThat(orderCount()).isZero();
        assertThat(inventoryLogMapper.selectCount(null)).isZero();
    }

    @Test
    void missingProductIsNotFoundAndOffShelfProductConflicts() {
        assertBusinessError(
                () -> customerOrderService.create(USER,
                        request(new CustomerOrderItemRequest(999999L, 1L))),
                HttpStatus.NOT_FOUND, "product not found");

        jdbcTemplate.update("update products set status='OFF_SHELF' where id=?", PRODUCT_ONE_ID);

        assertBusinessError(
                () -> customerOrderService.create(USER,
                        request(new CustomerOrderItemRequest(PRODUCT_ONE_ID, 1L))),
                HttpStatus.CONFLICT, "product is not on shelf");
        assertThat(orderCount()).isZero();
        assertThat(inventoryLogMapper.selectCount(null)).isZero();
    }

    @Test
    void insufficientSecondProductRollsBackFirstProductStockItemAndLog() {
        jdbcTemplate.update("update products set stock=0 where id=?", PRODUCT_TWO_ID);

        assertBusinessError(
                () -> customerOrderService.create(USER, new CustomerOrderCreateRequest(
                        List.of(new CustomerOrderItemRequest(PRODUCT_ONE_ID, 2L),
                                new CustomerOrderItemRequest(PRODUCT_TWO_ID, 1L)),
                        ADDRESS_ID, null)),
                HttpStatus.CONFLICT, "insufficient stock");

        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(5L);
        assertThat(stock(PRODUCT_TWO_ID)).isZero();
        assertThat(orderCount()).isZero();
        assertThat(itemMapper.selectCount(null)).isZero();
        assertThat(inventoryLogMapper.selectCount(null)).isZero();
    }

    @Test
    void cartCleanupRollsBackWhenOrderTransactionFailsAfterDeletion() {
        cartService.put(USER, new CartItemRequest(PRODUCT_ONE_ID, 2L));
        org.mockito.Mockito.doThrow(new IllegalStateException("response failed"))
                .when(itemMapper).selectList(any());

        assertThatThrownBy(() -> customerOrderService.create(USER,
                request(new CustomerOrderItemRequest(PRODUCT_ONE_ID, 2L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("response failed");

        assertThat(cartService.list(USER)).singleElement()
                .extracting(item -> item.productId())
                .isEqualTo(PRODUCT_ONE_ID);
        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(5L);
        assertThat(orderCount()).isZero();
        assertThat(inventoryLogMapper.selectCount(null)).isZero();
    }

    @Test
    void timeoutPublishFailureRollsBackOrderStockLogsAndCartCleanup() {
        cartService.put(USER, new CartItemRequest(PRODUCT_ONE_ID, 2L));
        doThrow(new IllegalStateException("timeout publish failed"))
                .when(timeoutDispatcher).dispatch(anyLong(), any(Duration.class));

        assertThatThrownBy(() -> customerOrderService.create(USER,
                request(new CustomerOrderItemRequest(PRODUCT_ONE_ID, 2L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("timeout publish failed");

        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(5L);
        assertThat(orderCount()).isZero();
        assertThat(itemMapper.selectCount(null)).isZero();
        assertThat(inventoryLogMapper.selectCount(null)).isZero();
        assertThat(cartService.list(USER)).singleElement()
                .extracting(item -> item.productId())
                .isEqualTo(PRODUCT_ONE_ID);
    }

    @Test
    void repeatedCancellationDoesNotRestoreStockOrLogTwice() {
        var order = customerOrderService.create(USER,
                request(new CustomerOrderItemRequest(PRODUCT_ONE_ID, 2L)));

        customerOrderService.cancel(USER, order.id());
        long stockAfterFirstCancel = stock(PRODUCT_ONE_ID);
        long logsAfterFirstCancel = inventoryLogMapper.selectCount(
                new LambdaQueryWrapper<InventoryLogEntity>()
                        .eq(InventoryLogEntity::getOrderId, order.id()));

        assertBusinessError(() -> customerOrderService.cancel(USER, order.id()),
                HttpStatus.CONFLICT, "order cannot be canceled");

        assertThat(stock(PRODUCT_ONE_ID)).isEqualTo(stockAfterFirstCancel).isEqualTo(5L);
        assertThat(inventoryLogMapper.selectCount(new LambdaQueryWrapper<InventoryLogEntity>()
                .eq(InventoryLogEntity::getOrderId, order.id())))
                .isEqualTo(logsAfterFirstCancel)
                .isEqualTo(2L);
    }

    @Test
    void legacyNullAndMalformedShippingSnapshotsReturnNull() {
        insertLegacyOrder(9601L, null);
        insertLegacyOrder(9602L, "{malformed");

        assertThat(customerOrderService.get(USER, 9601L).shippingAddress()).isNull();
        assertThat(customerOrderService.get(USER, 9602L).shippingAddress()).isNull();
    }

    private CustomerOrderCreateRequest request(CustomerOrderItemRequest... items) {
        return new CustomerOrderCreateRequest(List.of(items), ADDRESS_ID, "Task5 order");
    }

    private void assertBusinessError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
                                     HttpStatus status,
                                     String message) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(status);
                    assertThat(exception).hasMessage(message);
                });
    }

    private long orderCount() {
        return orderMapper.selectCount(new LambdaQueryWrapper<CustomerOrderEntity>()
                .eq(CustomerOrderEntity::getUserId, USER.id()));
    }

    private long stock(long productId) {
        return productMapper.selectById(productId).getStock();
    }

    private void insertProduct(long id, String sku, String name, String price, long stock, String status) {
        jdbcTemplate.update("""
                        insert into products
                          (id, merchant_id, sku, name, price, stock, hot_score, main_image, detail_images,
                           spec, unit, status, sort_order, updated_at)
                        values (?, 1, ?, ?, ?, ?, 0, '', '', '', 'piece', ?, 0, current_timestamp)
                        """,
                id, sku, name, new BigDecimal(price), stock, status);
    }

    private void insertAddress(long id, long userId, String name, String phone,
                               String province, String city, String district, String detail) {
        jdbcTemplate.update("""
                        insert into shipping_addresses
                          (id, user_id, receiver_name, receiver_phone, province, city, district, detail,
                           default_address, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, true, current_timestamp, current_timestamp)
                        """,
                id, userId, name, phone, province, city, district, detail);
    }

    private void insertLegacyOrder(long id, String snapshot) {
        jdbcTemplate.update("""
                        insert into customer_orders
                          (id, order_no, user_id, address_id, shipping_snapshot, total_amount, status, remark,
                           version, created_at, updated_at)
                        values (?, ?, ?, ?, ?, 0.00, 'PENDING_PAYMENT', null, 0, current_timestamp, current_timestamp)
                        """,
                id, "LEGACY-" + id, USER.id(), ADDRESS_ID, snapshot);
    }

    private void cleanFixtures() {
        List<Long> orderIds = jdbcTemplate.queryForList(
                "select id from customer_orders where user_id=?", Long.class, USER.id());
        for (Long orderId : orderIds) {
            jdbcTemplate.update("delete from inventory_logs where order_id=?", orderId);
            jdbcTemplate.update("delete from customer_order_items where order_id=?", orderId);
            jdbcTemplate.update("delete from payment_orders where order_id=?", orderId);
        }
        jdbcTemplate.update("delete from customer_orders where user_id=?", USER.id());
        jdbcTemplate.update("delete from cart_items where user_id=?", USER.id());
        jdbcTemplate.update("delete from shipping_addresses where id in (?, ?)", ADDRESS_ID, OTHER_ADDRESS_ID);
        jdbcTemplate.update("delete from products where id in (?, ?)", PRODUCT_ONE_ID, PRODUCT_TWO_ID);
    }
}

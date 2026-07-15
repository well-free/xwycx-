package org.example.service;

import org.example.auth.UserRole;
import org.example.infrastructure.mq.CustomerOrderTimeoutDispatcher;
import org.example.infrastructure.mybatis.mapper.CustomerOrderMapper;
import org.example.web.BusinessException;
import org.example.web.dto.AuthUserResponse;
import org.example.web.dto.CustomerOrderCreateRequest;
import org.example.web.dto.CustomerOrderItemRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:customer_order_ownership;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
})
class CustomerOrderOwnershipServiceTest {
    private static final AuthUserResponse SOURCE =
            new AuthUserResponse(801L, "wx_source", UserRole.CUSTOMER);
    private static final long TARGET_USER_ID = 802L;
    private static final long ADDRESS_ID = 9801L;
    private static final long PRODUCT_ID = 9301L;

    @Autowired
    private CustomerOrderOwnershipService ownershipService;

    @Autowired
    private CustomerOrderService customerOrderService;

    @Autowired
    private CartService cartService;

    @Autowired
    private AddressService addressService;

    @Autowired
    private CustomerOrderMapper orderMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CustomerOrderTimeoutDispatcher timeoutDispatcher;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        jdbcTemplate.update("""
                        insert into products
                          (id, merchant_id, sku, name, price, stock, hot_score, main_image, detail_images,
                           spec, unit, status, sort_order, updated_at)
                        values (?, 1, 'OWNERSHIP', 'Ownership', 10.00, 5, 0, '', '', '', 'piece',
                                'ON_SHELF', 0, current_timestamp)
                        """, PRODUCT_ID);
        jdbcTemplate.update("""
                        insert into shipping_addresses
                          (id, user_id, receiver_name, receiver_phone, province, city, district, detail,
                           default_address, created_at, updated_at)
                        values (?, ?, 'Source', '13800000801', 'Zhejiang', 'Hangzhou', 'Xihu', 'No. 1',
                                true, current_timestamp, current_timestamp)
                        """, ADDRESS_ID, SOURCE.id());
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    @Test
    void mergeAndCreateCannotLeaveOrderOwnedByRetiredSourceAccount() throws Exception {
        CountDownLatch mergeTransactionStarted = new CountDownLatch(1);
        CountDownLatch allowMerge = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var merge = executor.submit(() -> ownershipService.withUserLocks(
                    List.of(TARGET_USER_ID, SOURCE.id()),
                    () -> cartService.withUserLocks(List.of(SOURCE.id(), TARGET_USER_ID),
                            () -> addressService.withUserLocks(List.of(SOURCE.id(), TARGET_USER_ID),
                                    () -> {
                                        transactionTemplate.executeWithoutResult(status -> {
                                            mergeTransactionStarted.countDown();
                                            await(allowMerge);
                                            cartService.mergeCartOwnershipLocked(SOURCE.id(), TARGET_USER_ID);
                                            addressService.mergeOwnershipLocked(SOURCE.id(), TARGET_USER_ID);
                                            ownershipService.mergeOwnershipLocked(SOURCE.id(), TARGET_USER_ID);
                                        });
                                        return null;
                                    }))));
            assertThat(mergeTransactionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var create = executor.submit(() -> customerOrderService.create(SOURCE,
                    new CustomerOrderCreateRequest(
                            List.of(new CustomerOrderItemRequest(PRODUCT_ID, 1L)), ADDRESS_ID, null)));
            Thread.sleep(100L);
            assertThat(create).isNotDone();

            allowMerge.countDown();
            merge.get(5, TimeUnit.SECONDS);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> get(create))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("address not found");
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from customer_orders where user_id=?", Long.class, SOURCE.id())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from shipping_addresses where user_id=?", Long.class, SOURCE.id())).isZero();
    }

    private void cleanFixtures() {
        List<Long> orderIds = jdbcTemplate.queryForList(
                "select id from customer_orders where user_id in (?, ?)", Long.class, SOURCE.id(), TARGET_USER_ID);
        for (Long orderId : orderIds) {
            jdbcTemplate.update("delete from inventory_logs where order_id=?", orderId);
            jdbcTemplate.update("delete from customer_order_items where order_id=?", orderId);
        }
        jdbcTemplate.update("delete from customer_orders where user_id in (?, ?)", SOURCE.id(), TARGET_USER_ID);
        jdbcTemplate.update("delete from cart_items where user_id in (?, ?)", SOURCE.id(), TARGET_USER_ID);
        jdbcTemplate.update("delete from shipping_addresses where id=?", ADDRESS_ID);
        jdbcTemplate.update("delete from products where id=?", PRODUCT_ID);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("merge latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static Object get(java.util.concurrent.Future<?> future) throws Throwable {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            throw exception.getCause();
        }
    }
}

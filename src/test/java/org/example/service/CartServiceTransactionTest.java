package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.auth.UserRole;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mybatis.entity.CartItemEntity;
import org.example.infrastructure.mybatis.mapper.CartItemMapper;
import org.example.web.dto.AuthUserResponse;
import org.example.web.dto.CartItemRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false"
})
class CartServiceTransactionTest {
    private static final AuthUserResponse USER =
            new AuthUserResponse(303L, "13800000303", UserRole.CUSTOMER);

    @Autowired
    private CartService cartService;

    @SpyBean
    private CartItemMapper cartItemMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private DistributedLockService lockService;

    @BeforeEach
    void setUp() {
        deleteTestCart();
        reset(lockService);
    }

    @AfterEach
    void tearDown() {
        deleteTestCart();
    }

    @Test
    void removePurchasedRunsAfterCommitWithUserThenSortedProductLocks() {
        cartService.put(USER, new CartItemRequest(1L, 2L));
        clearInvocations(lockService);

        transactionTemplate.executeWithoutResult(status -> {
            cartService.removePurchased(USER.id(), List.of(9999L, 1L, 9999L));

            assertThat(cartService.list(USER)).hasSize(1);
            verifyNoInteractions(lockService);
        });

        assertThat(cartService.list(USER)).isEmpty();
        InOrder locks = inOrder(lockService);
        locks.verify(lockService).withLock(eq("cart:user:303"), any());
        locks.verify(lockService).withLock(eq("cart:303:1"), any());
        locks.verify(lockService).withLock(eq("cart:303:9999"), any());
        locks.verifyNoMoreInteractions();
    }

    @Test
    void removePurchasedDoesNothingAfterAmbientTransactionRollsBack() {
        cartService.put(USER, new CartItemRequest(1L, 2L));
        clearInvocations(lockService);

        transactionTemplate.executeWithoutResult(status -> {
            cartService.removePurchased(USER.id(), List.of(1L));
            assertThat(cartService.list(USER)).hasSize(1);
            status.setRollbackOnly();
        });

        assertThat(cartService.list(USER)).hasSize(1);
        verifyNoInteractions(lockService);
    }

    @Test
    void removePurchasedKeepsCartRowUpdatedAfterSnapshot() {
        cartService.put(USER, new CartItemRequest(1L, 2L));
        TransactionTemplate concurrentTransaction =
                new TransactionTemplate(transactionTemplate.getTransactionManager());
        concurrentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        transactionTemplate.executeWithoutResult(status -> {
            cartService.removePurchased(USER.id(), List.of(1L));
            concurrentTransaction.executeWithoutResult(concurrentStatus ->
                    cartService.put(USER, new CartItemRequest(1L, 3L)));
        });

        assertThat(cartService.list(USER)).singleElement()
                .extracting(item -> item.quantity())
                .isEqualTo(3L);
    }

    @Test
    void removePurchasedKeepsSameQuantityUpdateWithCapturedTimestamp() {
        cartService.put(USER, new CartItemRequest(1L, 2L));
        Long cartItemId = jdbcTemplate.queryForObject(
                "select id from cart_items where user_id=? and product_id=?",
                Long.class, USER.id(), 1L);
        Long originalVersion = jdbcTemplate.queryForObject(
                "select version from cart_items where id=?", Long.class, cartItemId);
        Instant originalUpdatedAt = jdbcTemplate.queryForObject(
                "select updated_at from cart_items where id=?", Timestamp.class, cartItemId).toInstant();
        TransactionTemplate concurrentTransaction =
                new TransactionTemplate(transactionTemplate.getTransactionManager());
        concurrentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        transactionTemplate.executeWithoutResult(status -> {
            cartService.removePurchased(USER.id(), List.of(1L));
            concurrentTransaction.executeWithoutResult(concurrentStatus -> {
                cartService.put(USER, new CartItemRequest(1L, 2L));
                assertThat(jdbcTemplate.queryForObject(
                        "select version from cart_items where id=?", Long.class, cartItemId))
                        .isEqualTo(originalVersion + 1);
                jdbcTemplate.update("update cart_items set updated_at=? where id=?",
                        Timestamp.from(originalUpdatedAt), cartItemId);
            });
        });

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from cart_items where id=?", Long.class, cartItemId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select version from cart_items where id=?", Long.class, cartItemId))
                .isEqualTo(originalVersion + 1);
    }

    @Test
    void removePurchasedCleanupFailureDoesNotEscapeAfterCommit() {
        cartService.put(USER, new CartItemRequest(1L, 2L));
        doThrow(new IllegalStateException("cleanup failed"))
                .when(cartItemMapper).delete(any());

        assertThatCode(() -> transactionTemplate.executeWithoutResult(status ->
                cartService.removePurchased(USER.id(), List.of(1L))))
                .doesNotThrowAnyException();

        assertThat(cartService.list(USER)).hasSize(1);
    }

    private void deleteTestCart() {
        jdbcTemplate.update("delete from cart_items where user_id=?", USER.id());
    }
}

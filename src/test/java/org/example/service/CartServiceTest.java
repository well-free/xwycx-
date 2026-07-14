package org.example.service;

import org.example.auth.UserRole;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mybatis.entity.CartItemEntity;
import org.example.infrastructure.mybatis.entity.ProductEntity;
import org.example.infrastructure.mybatis.mapper.CartItemMapper;
import org.example.infrastructure.mybatis.mapper.ProductMapper;
import org.example.web.BusinessException;
import org.example.web.dto.AuthUserResponse;
import org.example.web.dto.CartItemRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false"
})
@Transactional
class CartServiceTest {
    private static final AuthUserResponse USER_ONE =
            new AuthUserResponse(101L, "13800000101", UserRole.CUSTOMER);
    private static final AuthUserResponse USER_TWO =
            new AuthUserResponse(202L, "13800000202", UserRole.CUSTOMER);

    @Autowired
    private CartService cartService;

    @SpyBean
    private CartItemMapper cartItemMapper;

    @Autowired
    private ProductMapper productMapper;

    @SpyBean
    private DistributedLockService lockService;

    @BeforeEach
    void setUp() {
        reset(lockService);
    }

    @Test
    void repeatedPutSetsAbsoluteQuantityAndUsesStableLockKey() {
        cartService.put(USER_ONE, new CartItemRequest(1L, 2L));

        var item = cartService.put(USER_ONE, new CartItemRequest(1L, 3L));

        assertThat(item.quantity()).isEqualTo(3L);
        assertThat(cartItemMapper.selectById(item.id()).getQuantity()).isEqualTo(3L);
        InOrder locks = inOrder(lockService);
        locks.verify(lockService).withLock(eq("cart:user:101"), any());
        locks.verify(lockService).withLock(eq("cart:101:1"), any());
        locks.verify(lockService).withLock(eq("cart:user:101"), any());
        locks.verify(lockService).withLock(eq("cart:101:1"), any());
        locks.verifyNoMoreInteractions();
    }

    @Test
    void rejectsProductAndQuantityOutsideAllowedRange() {
        assertThatThrownBy(() -> cartService.put(USER_ONE, new CartItemRequest(0L, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("productId must be positive");
        assertThatThrownBy(() -> cartService.put(USER_ONE, new CartItemRequest(1L, 0L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("quantity must be between 1 and 99999");
        assertThatThrownBy(() -> cartService.put(USER_ONE, new CartItemRequest(1L, 100000L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("quantity must be between 1 and 99999");
    }

    @Test
    void rejectsMissingProductAsNotFoundAndOffShelfProductAsConflict() {
        BusinessException missing = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> cartService.put(USER_ONE, new CartItemRequest(9999L, 1L)),
                BusinessException.class);
        assertThat(missing.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing).hasMessage("product not found");

        ProductEntity product = productMapper.selectById(1L);
        product.setStatus("OFF_SHELF");
        productMapper.updateById(product);

        assertThatThrownBy(() -> cartService.put(USER_ONE, new CartItemRequest(1L, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("product is not on sale");
    }

    @Test
    void listKeepsDownAndDeletedProductsAsUnavailable() {
        cartService.put(USER_ONE, new CartItemRequest(1L, 2L));
        ProductEntity product = productMapper.selectById(1L);
        product.setStatus("OFF_SHELF");
        productMapper.updateById(product);

        var down = cartService.list(USER_ONE).getFirst();
        assertThat(down.available()).isFalse();
        assertThat(down.currentPrice()).isEqualByComparingTo(new BigDecimal("12.80"));

        productMapper.deleteById(1L);
        var missing = cartService.list(USER_ONE).getFirst();
        assertThat(missing.available()).isFalse();
        assertThat(missing.sku()).isEmpty();
        assertThat(missing.name()).isEmpty();
        assertThat(missing.currentPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(missing.currentStock()).isZero();
    }

    @Test
    void listAndRemoveAreIsolatedByUser() {
        cartService.put(USER_ONE, new CartItemRequest(1L, 2L));
        cartService.put(USER_TWO, new CartItemRequest(1L, 4L));

        assertThat(cartService.list(USER_ONE)).extracting(item -> item.quantity()).containsExactly(2L);
        assertThat(cartService.list(USER_TWO)).extracting(item -> item.quantity()).containsExactly(4L);

        clearInvocations(lockService);
        cartService.remove(USER_ONE, 1L);

        assertThat(cartService.list(USER_ONE)).isEmpty();
        assertThat(cartService.list(USER_TWO)).hasSize(1);
        InOrder locks = inOrder(lockService);
        locks.verify(lockService).withLock(eq("cart:user:101"), any());
        locks.verify(lockService).withLock(eq("cart:101:1"), any());
        locks.verifyNoMoreInteractions();
    }

    @Test
    void withUserLocksSortsAndDeduplicatesUserIds() {
        String result = cartService.withUserLocks(List.of(202L, 101L, 202L), () -> "done");

        assertThat(result).isEqualTo("done");
        InOrder locks = inOrder(lockService);
        locks.verify(lockService).withLock(eq("cart:user:101"), any());
        locks.verify(lockService).withLock(eq("cart:user:202"), any());
        locks.verifyNoMoreInteractions();
    }

    @Test
    void removePurchasedRejectsInvalidProductIdsWithoutPartialCleanup() {
        cartService.put(USER_ONE, new CartItemRequest(1L, 2L));
        clearInvocations(lockService);

        assertThatThrownBy(() -> cartService.removePurchased(USER_ONE.id(), Arrays.asList(1L, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("productIds must contain only positive values");
        assertThatThrownBy(() -> cartService.removePurchased(USER_ONE.id(), List.of(1L, 0L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("productIds must contain only positive values");
        assertThat(cartService.list(USER_ONE)).hasSize(1);
        verifyNoInteractions(lockService);
    }

    @Test
    void putRejectsZeroRowUpdateAsConflict() {
        cartService.put(USER_ONE, new CartItemRequest(1L, 2L));
        doReturn(0).when(cartItemMapper).updateById(any(CartItemEntity.class));

        assertThatThrownBy(() -> cartService.put(USER_ONE, new CartItemRequest(1L, 3L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("cart item update conflicted");
    }
}

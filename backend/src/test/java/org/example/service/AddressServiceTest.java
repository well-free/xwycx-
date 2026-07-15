package org.example.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.auth.UserRole;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mybatis.entity.ShippingAddressEntity;
import org.example.infrastructure.mybatis.mapper.ShippingAddressMapper;
import org.example.web.BusinessException;
import org.example.web.dto.AddressRequest;
import org.example.web.dto.AuthUserResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false"
})
class AddressServiceTest {
    private static final AuthUserResponse USER_ONE =
            new AuthUserResponse(401L, "13800000401", UserRole.CUSTOMER);
    private static final AuthUserResponse USER_TWO =
            new AuthUserResponse(402L, "13800000402", UserRole.CUSTOMER);

    @Autowired
    private AddressService addressService;

    @MockitoSpyBean
    private ShippingAddressMapper addressMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private DistributedLockService lockService;

    @BeforeEach
    void setUp() {
        deleteTestAddresses();
        reset(lockService);
    }

    @AfterEach
    void tearDown() {
        deleteTestAddresses();
    }

    @Test
    void firstAddressBecomesDefaultAndMutationsUseStableUserLock() {
        var created = addressService.create(USER_ONE, request("First", false));

        assertThat(created.defaultAddress()).isTrue();
        assertThat(addressService.requireOwned(USER_ONE, created.id()).isDefaultAddress()).isTrue();
        verify(lockService).withLock(eq("address:user:401"), any());
        verifyNoMoreInteractions(lockService);

        clearInvocations(lockService);
        addressService.update(USER_ONE, created.id(), request("Updated", false));
        addressService.delete(USER_ONE, created.id());

        verify(lockService, times(2)).withLock(eq("address:user:401"), any());
        verifyNoMoreInteractions(lockService);
    }

    @Test
    void settingDefaultClearsOnlyOtherAddressesOwnedBySameUser() {
        var first = addressService.create(USER_ONE, request("First", false));
        var second = addressService.create(USER_ONE, request("Second", true));
        var otherUser = addressService.create(USER_TWO, request("Other user", true));

        assertThat(defaultIds(USER_ONE.id())).containsExactly(second.id());
        assertThat(defaultIds(USER_TWO.id())).containsExactly(otherUser.id());

        addressService.update(USER_ONE, first.id(), request("First again", true));

        assertThat(defaultIds(USER_ONE.id())).containsExactly(first.id());
        assertThat(defaultIds(USER_TWO.id())).containsExactly(otherUser.id());
    }

    @Test
    void clearingCurrentDefaultPromotesMostRecentlyUpdatedOtherAndKeepsSingleAddressDefault() {
        var first = addressService.create(USER_ONE, request("First", false));
        var second = addressService.create(USER_ONE, request("Second", false));
        var third = addressService.create(USER_ONE, request("Third", false));
        setUpdatedAt(second.id(), Instant.parse("2026-07-13T01:00:00Z"));
        setUpdatedAt(third.id(), Instant.parse("2026-07-13T02:00:00Z"));

        var updated = addressService.update(USER_ONE, first.id(), request("First", false));

        assertThat(updated.defaultAddress()).isFalse();
        assertThat(defaultIds(USER_ONE.id())).containsExactly(third.id());

        addressService.delete(USER_ONE, second.id());
        addressService.delete(USER_ONE, third.id());
        var onlyAddress = addressService.update(USER_ONE, first.id(), request("Only", false));

        assertThat(onlyAddress.defaultAddress()).isTrue();
        assertThat(defaultIds(USER_ONE.id())).containsExactly(first.id());
    }

    @Test
    void deletingDefaultPromotesMostRecentlyUpdatedRemainingAddress() {
        var first = addressService.create(USER_ONE, request("First", false));
        var second = addressService.create(USER_ONE, request("Second", false));
        var third = addressService.create(USER_ONE, request("Third", false));
        setUpdatedAt(second.id(), Instant.parse("2026-07-13T03:00:00Z"));
        setUpdatedAt(third.id(), Instant.parse("2026-07-13T04:00:00Z"));

        addressService.delete(USER_ONE, first.id());

        assertThat(defaultIds(USER_ONE.id())).containsExactly(third.id());
        assertThat(addressService.list(USER_ONE))
                .extracting(address -> address.id())
                .containsExactly(third.id(), second.id());
    }

    @Test
    void requireOwnedUpdateAndDeleteHideAnotherUsersAddress() {
        var created = addressService.create(USER_ONE, request("Private", false));

        assertNotFound(() -> addressService.requireOwned(USER_TWO, created.id()));
        assertNotFound(() -> addressService.update(USER_TWO, created.id(), request("Stolen", true)));
        assertNotFound(() -> addressService.delete(USER_TWO, created.id()));

        ShippingAddressEntity unchanged = addressService.requireOwned(USER_ONE, created.id());
        assertThat(unchanged.getReceiverName()).isEqualTo("Private");
        assertThat(unchanged.isDefaultAddress()).isTrue();
    }

    @Test
    void withUserLocksSortsAndDeduplicatesAddressOwners() {
        String result = addressService.withUserLocks(
                java.util.List.of(USER_TWO.id(), USER_ONE.id(), USER_TWO.id()), () -> "done");

        assertThat(result).isEqualTo("done");
        InOrder locks = inOrder(lockService);
        locks.verify(lockService).withLock(eq("address:user:401"), any());
        locks.verify(lockService).withLock(eq("address:user:402"), any());
        locks.verifyNoMoreInteractions();
    }

    @Test
    void mainUpdateUsesOwnerScopedWrapperAndNeverUpdateById() {
        var created = addressService.create(USER_ONE, request("First", false));
        clearInvocations(addressMapper);

        addressService.update(USER_ONE, created.id(), request("Updated", false));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ShippingAddressEntity>> wrapper =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(addressMapper).update(isNull(), wrapper.capture());
        verify(addressMapper, never()).updateById(
                org.mockito.ArgumentMatchers.<ShippingAddressEntity>any());
        assertThat(wrapper.getValue().getSqlSegment()).contains("id", "user_id");
    }

    @Test
    void mergeOwnershipRequiresCallerTransaction() {
        assertThatThrownBy(() -> addressService.mergeOwnershipLocked(USER_ONE.id(), USER_TWO.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("address ownership merge requires a transaction");
    }

    private AddressRequest request(String receiverName, boolean defaultAddress) {
        return new AddressRequest(receiverName, "13800000401", "Zhejiang", "Hangzhou",
                "Xihu", "No. 1 Road", defaultAddress);
    }

    private java.util.List<Long> defaultIds(long userId) {
        return addressMapper.selectList(new LambdaQueryWrapper<ShippingAddressEntity>()
                        .eq(ShippingAddressEntity::getUserId, userId)
                        .eq(ShippingAddressEntity::isDefaultAddress, true))
                .stream()
                .map(ShippingAddressEntity::getId)
                .toList();
    }

    private void setUpdatedAt(long addressId, Instant updatedAt) {
        jdbcTemplate.update("update shipping_addresses set updated_at=? where id=?",
                Timestamp.from(updatedAt), addressId);
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception).hasMessage("address not found");
                });
    }

    private void deleteTestAddresses() {
        addressMapper.delete(new LambdaQueryWrapper<ShippingAddressEntity>()
                .in(ShippingAddressEntity::getUserId, USER_ONE.id(), USER_TWO.id()));
    }
}

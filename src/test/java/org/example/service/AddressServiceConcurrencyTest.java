package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.auth.UserRole;
import org.example.infrastructure.mybatis.entity.ShippingAddressEntity;
import org.example.infrastructure.mybatis.mapper.ShippingAddressMapper;
import org.example.web.BusinessException;
import org.example.web.dto.AddressRequest;
import org.example.web.dto.AuthUserResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false"
})
class AddressServiceConcurrencyTest {
    private static final AuthUserResponse SOURCE =
            new AuthUserResponse(601L, "13800000601", UserRole.CUSTOMER);
    private static final AuthUserResponse TARGET =
            new AuthUserResponse(602L, "13800000602", UserRole.CUSTOMER);

    @Autowired
    private AddressService addressService;

    @Autowired
    private ShippingAddressMapper addressMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        deleteTestAddresses();
    }

    @AfterEach
    void tearDown() {
        deleteTestAddresses();
    }

    @Test
    void standaloneMutationCommitsDespiteAmbientTransactionRollback() {
        transactionTemplate.executeWithoutResult(status -> {
            addressService.create(SOURCE, request("Committed", false));
            status.setRollbackOnly();
        });

        assertThat(addressService.list(SOURCE))
                .singleElement()
                .extracting(address -> address.receiverName())
                .isEqualTo("Committed");
    }

    @Test
    void competingDefaultMutationsSerializeUntilFirstCommit() throws Exception {
        var first = addressService.create(SOURCE, request("First", false));
        var second = addressService.create(SOURCE, request("Second", false));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstCommitted = new CountDownLatch(1);
        CountDownLatch releaseFirstLock = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        try {
            Future<?> firstMutation = executor.submit(() -> addressService.withUserLocks(
                    List.of(SOURCE.id()), () -> {
                        addressService.update(SOURCE, first.id(), request("First", true));
                        firstCommitted.countDown();
                        await(releaseFirstLock);
                        return null;
                    }));
            assertThat(firstCommitted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> secondMutation = executor.submit(() -> {
                secondStarted.countDown();
                addressService.update(SOURCE, second.id(), request("Second", true));
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> secondMutation.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirstLock.countDown();
            firstMutation.get(5, TimeUnit.SECONDS);
            secondMutation.get(5, TimeUnit.SECONDS);

            assertThat(defaultIds(TARGET.id())).isEmpty();
            assertThat(defaultIds(SOURCE.id())).containsExactly(second.id());
        } finally {
            releaseFirstLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void mergeBlocksCompetingSourceUpdateAndKeepsTargetDefault() throws Exception {
        var targetDefault = addressService.create(TARGET, request("Target", true));
        var sourceDefault = addressService.create(SOURCE, request("Source", true));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch mergeApplied = new CountDownLatch(1);
        CountDownLatch releaseMerge = new CountDownLatch(1);
        CountDownLatch updateStarted = new CountDownLatch(1);

        try {
            Future<?> merge = executor.submit(() -> addressService.withUserLocks(
                    List.of(SOURCE.id(), TARGET.id()), () -> transactionTemplate.execute(status -> {
                        addressService.mergeOwnershipLocked(SOURCE.id(), TARGET.id());
                        mergeApplied.countDown();
                        await(releaseMerge);
                        return null;
                    })));
            assertThat(mergeApplied.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> competingUpdate = executor.submit(() -> {
                updateStarted.countDown();
                return catchThrowable(() -> addressService.update(
                        SOURCE, sourceDefault.id(), request("Too late", true)));
            });
            assertThat(updateStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> competingUpdate.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseMerge.countDown();
            merge.get(5, TimeUnit.SECONDS);
            Throwable updateFailure = competingUpdate.get(5, TimeUnit.SECONDS);

            assertThat(updateFailure)
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getMessage()).isEqualTo("address not found"));
            assertThat(addressService.list(SOURCE)).isEmpty();
            assertThat(defaultIds(TARGET.id())).containsExactly(targetDefault.id());
            assertThat(addressService.requireOwned(TARGET, sourceDefault.id()).isDefaultAddress()).isFalse();
        } finally {
            releaseMerge.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void mergeWithoutExistingDefaultPromotesNewestAddressDeterministically() {
        var target = addressService.create(TARGET, request("Target", false));
        var sourceOlder = addressService.create(SOURCE, request("Older", false));
        var sourceNewest = addressService.create(SOURCE, request("Newest", false));
        jdbcTemplate.update("update shipping_addresses set default_address=false");
        setUpdatedAt(target.id(), Instant.parse("2026-07-13T01:00:00Z"));
        setUpdatedAt(sourceOlder.id(), Instant.parse("2026-07-13T02:00:00Z"));
        setUpdatedAt(sourceNewest.id(), Instant.parse("2026-07-13T03:00:00Z"));

        addressService.withUserLocks(List.of(SOURCE.id(), TARGET.id()),
                () -> transactionTemplate.execute(status -> {
                    addressService.mergeOwnershipLocked(SOURCE.id(), TARGET.id());
                    return null;
                }));

        assertThat(addressService.list(SOURCE)).isEmpty();
        assertThat(defaultIds(TARGET.id())).containsExactly(sourceNewest.id());
    }

    private AddressRequest request(String receiverName, boolean defaultAddress) {
        return new AddressRequest(receiverName, "13800000601", "Zhejiang", "Hangzhou",
                "Xihu", "No. 1 Road", defaultAddress);
    }

    private List<Long> defaultIds(long userId) {
        return addressMapper.selectList(new LambdaQueryWrapper<ShippingAddressEntity>()
                        .eq(ShippingAddressEntity::getUserId, userId)
                        .eq(ShippingAddressEntity::isDefaultAddress, true)
                        .orderByAsc(ShippingAddressEntity::getId))
                .stream()
                .map(ShippingAddressEntity::getId)
                .toList();
    }

    private void setUpdatedAt(long addressId, Instant updatedAt) {
        jdbcTemplate.update("update shipping_addresses set updated_at=? where id=?",
                Timestamp.from(updatedAt), addressId);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for test coordination", exception);
        }
    }

    private void deleteTestAddresses() {
        addressMapper.delete(new LambdaQueryWrapper<ShippingAddressEntity>()
                .in(ShippingAddressEntity::getUserId, SOURCE.id(), TARGET.id()));
    }
}

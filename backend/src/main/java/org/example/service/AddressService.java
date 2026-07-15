package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mybatis.entity.ShippingAddressEntity;
import org.example.infrastructure.mybatis.mapper.ShippingAddressMapper;
import org.example.web.BusinessException;
import org.example.web.dto.AddressRequest;
import org.example.web.dto.AddressResponse;
import org.example.web.dto.AuthUserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Service
public class AddressService {
    private static final Comparator<ShippingAddressEntity> NEWEST_FIRST =
            Comparator.comparing(ShippingAddressEntity::getUpdatedAt, Comparator.reverseOrder())
                    .thenComparing(ShippingAddressEntity::getId, Comparator.reverseOrder());

    private final ShippingAddressMapper addressMapper;
    private final DistributedLockService lockService;
    private final TransactionTemplate mutationTransactionTemplate;

    public AddressService(ShippingAddressMapper addressMapper,
                          DistributedLockService lockService,
                          TransactionTemplate transactionTemplate) {
        this.addressMapper = addressMapper;
        this.lockService = lockService;
        this.mutationTransactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        this.mutationTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<AddressResponse> list(AuthUserResponse user) {
        return addressesForUser(user.id()).stream()
                .map(this::toResponse)
                .toList();
    }

    public AddressResponse create(AuthUserResponse user, AddressRequest request) {
        return withUserLocks(List.of(user.id()),
                () -> mutationTransactionTemplate.execute(status -> createLocked(user.id(), request)));
    }

    public AddressResponse update(AuthUserResponse user, long addressId, AddressRequest request) {
        return withUserLocks(List.of(user.id()),
                () -> mutationTransactionTemplate.execute(status -> updateLocked(user, addressId, request)));
    }

    public void delete(AuthUserResponse user, long addressId) {
        withUserLocks(List.of(user.id()), () -> mutationTransactionTemplate.execute(status -> {
            deleteLocked(user, addressId);
            return null;
        }));
    }

    public <T> T withUserLocks(Collection<Long> userIds, Supplier<T> action) {
        if (userIds == null || userIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw BusinessException.badRequest("userIds must contain only positive values");
        }
        List<Long> ids = userIds.stream().distinct().sorted().toList();
        return withUserLocks(ids, 0, action);
    }

    public void mergeOwnershipLocked(long sourceUserId, long targetUserId) {
        if (sourceUserId <= 0 || targetUserId <= 0 || sourceUserId == targetUserId) {
            throw BusinessException.badRequest("distinct positive address owners required");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("address ownership merge requires a transaction");
        }

        List<ShippingAddressEntity> sourceAddresses = addressesForUser(sourceUserId);
        List<ShippingAddressEntity> targetAddresses = addressesForUser(targetUserId);
        ShippingAddressEntity defaultWinner = chooseMergeDefault(sourceAddresses, targetAddresses);
        Long defaultWinnerId = defaultWinner == null ? null : defaultWinner.getId();
        Instant now = Instant.now();

        for (ShippingAddressEntity target : targetAddresses) {
            boolean shouldBeDefault = Objects.equals(target.getId(), defaultWinnerId);
            if (target.isDefaultAddress() != shouldBeDefault) {
                updateDefault(targetUserId, target.getId(), shouldBeDefault, now);
            }
        }
        for (ShippingAddressEntity source : sourceAddresses) {
            transferOwner(sourceUserId, targetUserId, source.getId(),
                    Objects.equals(source.getId(), defaultWinnerId), now);
        }
    }

    public ShippingAddressEntity requireOwned(AuthUserResponse user, long addressId) {
        ShippingAddressEntity address = addressMapper.selectOne(
                new LambdaQueryWrapper<ShippingAddressEntity>()
                        .eq(ShippingAddressEntity::getId, addressId)
                        .eq(ShippingAddressEntity::getUserId, user.id())
                        .last("limit 1"));
        if (address == null) {
            throw BusinessException.notFound("address not found");
        }
        return address;
    }

    private AddressResponse createLocked(long userId, AddressRequest request) {
        boolean firstAddress = addressMapper.selectCount(new LambdaQueryWrapper<ShippingAddressEntity>()
                .eq(ShippingAddressEntity::getUserId, userId)) == 0;
        boolean makeDefault = firstAddress || request.defaultAddress();
        Instant now = Instant.now();
        if (makeDefault && !firstAddress) {
            clearDefaultsExcept(userId, null, now);
        }

        ShippingAddressEntity address = new ShippingAddressEntity();
        address.setId(IdWorker.getId());
        address.setUserId(userId);
        address.setCreatedAt(now);
        applyRequest(address, request, makeDefault, now);
        requireSingleRow(addressMapper.insert(address));
        return toResponse(address);
    }

    private AddressResponse updateLocked(AuthUserResponse user, long addressId, AddressRequest request) {
        ShippingAddressEntity address = requireOwned(user, addressId);
        boolean makeDefault = request.defaultAddress();
        Instant now = Instant.now();

        if (makeDefault) {
            clearDefaultsExcept(user.id(), addressId, now);
        } else if (address.isDefaultAddress()) {
            ShippingAddressEntity promoted = mostRecentlyUpdatedOther(user.id(), addressId);
            if (promoted == null) {
                makeDefault = true;
            } else {
                updateDefault(user.id(), promoted.getId(), true, now);
            }
        }

        updateMutableFields(user.id(), addressId, request, makeDefault, now);
        applyRequest(address, request, makeDefault, now);
        return toResponse(address);
    }

    private void deleteLocked(AuthUserResponse user, long addressId) {
        ShippingAddressEntity address = requireOwned(user, addressId);
        requireSingleRow(addressMapper.delete(new LambdaQueryWrapper<ShippingAddressEntity>()
                .eq(ShippingAddressEntity::getId, addressId)
                .eq(ShippingAddressEntity::getUserId, user.id())));
        if (!address.isDefaultAddress()) {
            return;
        }
        ShippingAddressEntity promoted = mostRecentlyUpdatedOther(user.id(), addressId);
        if (promoted != null) {
            updateDefault(user.id(), promoted.getId(), true, Instant.now());
        }
    }

    private List<ShippingAddressEntity> addressesForUser(long userId) {
        return addressMapper.selectList(new LambdaQueryWrapper<ShippingAddressEntity>()
                .eq(ShippingAddressEntity::getUserId, userId)
                .orderByDesc(ShippingAddressEntity::getUpdatedAt)
                .orderByDesc(ShippingAddressEntity::getId));
    }

    private ShippingAddressEntity mostRecentlyUpdatedOther(long userId, long addressId) {
        return addressMapper.selectOne(new LambdaQueryWrapper<ShippingAddressEntity>()
                .eq(ShippingAddressEntity::getUserId, userId)
                .ne(ShippingAddressEntity::getId, addressId)
                .orderByDesc(ShippingAddressEntity::getUpdatedAt)
                .orderByDesc(ShippingAddressEntity::getId)
                .last("limit 1"));
    }

    private ShippingAddressEntity chooseMergeDefault(List<ShippingAddressEntity> sourceAddresses,
                                                      List<ShippingAddressEntity> targetAddresses) {
        ShippingAddressEntity targetDefault = targetAddresses.stream()
                .filter(ShippingAddressEntity::isDefaultAddress)
                .findFirst()
                .orElse(null);
        if (targetDefault != null) {
            return targetDefault;
        }
        ShippingAddressEntity sourceDefault = sourceAddresses.stream()
                .filter(ShippingAddressEntity::isDefaultAddress)
                .findFirst()
                .orElse(null);
        if (sourceDefault != null) {
            return sourceDefault;
        }
        return Stream.concat(sourceAddresses.stream(), targetAddresses.stream())
                .min(NEWEST_FIRST)
                .orElse(null);
    }

    private void clearDefaultsExcept(long userId, Long retainedAddressId, Instant updatedAt) {
        addressesForUser(userId).stream()
                .filter(ShippingAddressEntity::isDefaultAddress)
                .filter(address -> !Objects.equals(address.getId(), retainedAddressId))
                .forEach(address -> updateDefault(userId, address.getId(), false, updatedAt));
    }

    private void updateDefault(long userId, long addressId, boolean defaultAddress, Instant updatedAt) {
        requireSingleRow(addressMapper.update(null, new LambdaUpdateWrapper<ShippingAddressEntity>()
                .eq(ShippingAddressEntity::getId, addressId)
                .eq(ShippingAddressEntity::getUserId, userId)
                .set(ShippingAddressEntity::isDefaultAddress, defaultAddress)
                .set(ShippingAddressEntity::getUpdatedAt, updatedAt)));
    }

    private void updateMutableFields(long userId,
                                     long addressId,
                                     AddressRequest request,
                                     boolean defaultAddress,
                                     Instant updatedAt) {
        requireSingleRow(addressMapper.update(null, new LambdaUpdateWrapper<ShippingAddressEntity>()
                .eq(ShippingAddressEntity::getId, addressId)
                .eq(ShippingAddressEntity::getUserId, userId)
                .set(ShippingAddressEntity::getReceiverName, request.receiverName())
                .set(ShippingAddressEntity::getReceiverPhone, request.receiverPhone())
                .set(ShippingAddressEntity::getProvince, request.province())
                .set(ShippingAddressEntity::getCity, request.city())
                .set(ShippingAddressEntity::getDistrict, request.district())
                .set(ShippingAddressEntity::getDetail, request.detail())
                .set(ShippingAddressEntity::isDefaultAddress, defaultAddress)
                .set(ShippingAddressEntity::getUpdatedAt, updatedAt)));
    }

    private void transferOwner(long sourceUserId,
                               long targetUserId,
                               long addressId,
                               boolean defaultAddress,
                               Instant updatedAt) {
        requireSingleRow(addressMapper.update(null, new LambdaUpdateWrapper<ShippingAddressEntity>()
                .eq(ShippingAddressEntity::getId, addressId)
                .eq(ShippingAddressEntity::getUserId, sourceUserId)
                .set(ShippingAddressEntity::getUserId, targetUserId)
                .set(ShippingAddressEntity::isDefaultAddress, defaultAddress)
                .set(ShippingAddressEntity::getUpdatedAt, updatedAt)));
    }

    private <T> T withUserLocks(List<Long> userIds, int index, Supplier<T> action) {
        if (index == userIds.size()) {
            return action.get();
        }
        long userId = userIds.get(index);
        return lockService.withLock(lockKey(userId),
                () -> withUserLocks(userIds, index + 1, action));
    }

    private void applyRequest(ShippingAddressEntity address,
                              AddressRequest request,
                              boolean defaultAddress,
                              Instant updatedAt) {
        address.setReceiverName(request.receiverName());
        address.setReceiverPhone(request.receiverPhone());
        address.setProvince(request.province());
        address.setCity(request.city());
        address.setDistrict(request.district());
        address.setDetail(request.detail());
        address.setDefaultAddress(defaultAddress);
        address.setUpdatedAt(updatedAt);
    }

    private AddressResponse toResponse(ShippingAddressEntity address) {
        return new AddressResponse(address.getId(), address.getUserId(), address.getReceiverName(),
                address.getReceiverPhone(), address.getProvince(), address.getCity(), address.getDistrict(),
                address.getDetail(), address.isDefaultAddress(), address.getCreatedAt(), address.getUpdatedAt());
    }

    private void requireSingleRow(int changed) {
        if (changed != 1) {
            throw BusinessException.conflict("address update conflicted");
        }
    }

    private String lockKey(long userId) {
        return "address:user:" + userId;
    }
}

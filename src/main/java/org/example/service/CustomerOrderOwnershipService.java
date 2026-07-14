package org.example.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mybatis.entity.CustomerOrderEntity;
import org.example.infrastructure.mybatis.mapper.CustomerOrderMapper;
import org.example.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

@Service
public class CustomerOrderOwnershipService {
    private final CustomerOrderMapper orderMapper;
    private final DistributedLockService lockService;

    public CustomerOrderOwnershipService(CustomerOrderMapper orderMapper,
                                         DistributedLockService lockService) {
        this.orderMapper = orderMapper;
        this.lockService = lockService;
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
            throw BusinessException.badRequest("distinct positive customer order owners required");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("customer order ownership merge requires a transaction");
        }
        orderMapper.update(null, new LambdaUpdateWrapper<CustomerOrderEntity>()
                .eq(CustomerOrderEntity::getUserId, sourceUserId)
                .set(CustomerOrderEntity::getUserId, targetUserId)
                .set(CustomerOrderEntity::getUpdatedAt, Instant.now())
                .setSql("version = version + 1"));
    }

    private <T> T withUserLocks(List<Long> userIds, int index, Supplier<T> action) {
        if (index == userIds.size()) {
            return action.get();
        }
        return lockService.withLock("customer-order:user:" + userIds.get(index),
                () -> withUserLocks(userIds, index + 1, action));
    }
}

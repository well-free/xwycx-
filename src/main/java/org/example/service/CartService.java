package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mybatis.entity.CartItemEntity;
import org.example.infrastructure.mybatis.entity.ProductEntity;
import org.example.infrastructure.mybatis.mapper.CartItemMapper;
import org.example.infrastructure.mybatis.mapper.ProductMapper;
import org.example.web.BusinessException;
import org.example.web.dto.AuthUserResponse;
import org.example.web.dto.CartItemRequest;
import org.example.web.dto.CartItemResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class CartService {
    private static final Logger log = LoggerFactory.getLogger(CartService.class);
    private static final long MAX_QUANTITY = 99999L;
    private static final String ON_SHELF = "ON_SHELF";

    private final CartItemMapper cartItemMapper;
    private final ProductMapper productMapper;
    private final DistributedLockService lockService;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate cleanupTransactionTemplate;

    public CartService(CartItemMapper cartItemMapper,
                       ProductMapper productMapper,
                       DistributedLockService lockService,
                       TransactionTemplate transactionTemplate) {
        this.cartItemMapper = cartItemMapper;
        this.productMapper = productMapper;
        this.lockService = lockService;
        this.transactionTemplate = transactionTemplate;
        this.cleanupTransactionTemplate = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.cleanupTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<CartItemResponse> list(AuthUserResponse user) {
        List<CartItemEntity> cartItems = cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, user.id())
                .orderByDesc(CartItemEntity::getUpdatedAt));
        if (cartItems.isEmpty()) {
            return List.of();
        }

        List<Long> productIds = cartItems.stream()
                .map(CartItemEntity::getProductId)
                .distinct()
                .toList();
        Map<Long, ProductEntity> products = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));
        return cartItems.stream()
                .map(item -> toResponse(item, products.get(item.getProductId())))
                .toList();
    }

    public CartItemResponse put(AuthUserResponse user, CartItemRequest request) {
        validateProductId(request.productId());
        validateQuantity(request.quantity());
        return withUserLocks(List.of(user.id()),
                () -> withProductLocks(user.id(), List.of(request.productId()), 0,
                        () -> transactionTemplate.execute(status -> putLocked(user.id(), request))));
    }

    public void remove(AuthUserResponse user, long productId) {
        validateProductId(productId);
        withUserLocks(List.of(user.id()),
                () -> withProductLocks(user.id(), List.of(productId), 0,
                        () -> transactionTemplate.execute(status -> cartItemMapper.delete(
                                new LambdaQueryWrapper<CartItemEntity>()
                                        .eq(CartItemEntity::getUserId, user.id())
                                        .eq(CartItemEntity::getProductId, productId)))));
    }

    public void removePurchased(long userId, Collection<Long> productIds) {
        if (userId <= 0) {
            throw BusinessException.badRequest("userId must be positive");
        }
        if (productIds == null || productIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw BusinessException.badRequest("productIds must contain only positive values");
        }
        List<Long> ids = productIds.stream()
                .distinct()
                .sorted()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        List<CartCleanupSnapshot> snapshots = cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                        .eq(CartItemEntity::getUserId, userId)
                        .in(CartItemEntity::getProductId, ids))
                .stream()
                .map(CartCleanupSnapshot::from)
                .toList();
        if (snapshots.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        removePurchasedNow(userId, ids, snapshots);
                    } catch (RuntimeException exception) {
                        log.error("cart cleanup after commit failed userId={} productIds={}",
                                userId, ids, exception);
                    }
                }
            });
            return;
        }
        removePurchasedNow(userId, ids, snapshots);
    }

    private void removePurchasedNow(long userId,
                                    List<Long> productIds,
                                    List<CartCleanupSnapshot> snapshots) {
        withUserLocks(List.of(userId), () -> withProductLocks(userId, productIds, 0,
                () -> cleanupTransactionTemplate.execute(status -> {
                    snapshots.forEach(this::deleteIfUnchanged);
                    return null;
                })));
    }

    private void deleteIfUnchanged(CartCleanupSnapshot snapshot) {
        cartItemMapper.delete(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getId, snapshot.id())
                .eq(CartItemEntity::getUserId, snapshot.userId())
                .eq(CartItemEntity::getProductId, snapshot.productId())
                .eq(CartItemEntity::getVersion, snapshot.version()));
    }

    public <T> T withUserLocks(Collection<Long> userIds, Supplier<T> action) {
        if (userIds == null || userIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw BusinessException.badRequest("userIds must contain only positive values");
        }
        List<Long> ids = userIds.stream().distinct().sorted().toList();
        return withUserLocks(ids, 0, action);
    }

    public void mergeCartOwnershipLocked(long sourceUserId, long targetUserId) {
        if (sourceUserId <= 0 || targetUserId <= 0 || sourceUserId == targetUserId) {
            throw BusinessException.badRequest("distinct positive cart owners required");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("cart ownership merge requires a transaction");
        }
        List<CartItemEntity> sourceItems = cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, sourceUserId));
        List<Long> productIds = sourceItems.stream()
                .map(CartItemEntity::getProductId)
                .distinct()
                .sorted()
                .toList();
        List<CartMutationLock> locks = List.of(sourceUserId, targetUserId).stream()
                .sorted()
                .flatMap(userId -> productIds.stream().map(productId -> new CartMutationLock(userId, productId)))
                .toList();
        withCartMutationLocks(locks, 0, () -> {
            mergeCartRows(sourceItems, targetUserId);
            return null;
        });
    }

    private <T> T withUserLocks(List<Long> userIds, int index, Supplier<T> action) {
        if (index == userIds.size()) {
            return action.get();
        }
        long userId = userIds.get(index);
        return lockService.withLock(userLockKey(userId),
                () -> withUserLocks(userIds, index + 1, action));
    }

    private <T> T withProductLocks(long userId, List<Long> productIds, int index, Supplier<T> action) {
        if (index == productIds.size()) {
            return action.get();
        }
        long productId = productIds.get(index);
        return lockService.withLock(lockKey(userId, productId),
                () -> withProductLocks(userId, productIds, index + 1, action));
    }

    private <T> T withCartMutationLocks(List<CartMutationLock> locks, int index, Supplier<T> action) {
        if (index == locks.size()) {
            return action.get();
        }
        CartMutationLock lock = locks.get(index);
        return lockService.withLock(lockKey(lock.userId(), lock.productId()),
                () -> withCartMutationLocks(locks, index + 1, action));
    }

    private void mergeCartRows(List<CartItemEntity> sourceItems, long targetUserId) {
        for (CartItemEntity sourceItem : sourceItems) {
            CartItemEntity targetItem = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItemEntity>()
                    .eq(CartItemEntity::getUserId, targetUserId)
                    .eq(CartItemEntity::getProductId, sourceItem.getProductId())
                    .last("limit 1"));
            if (targetItem == null) {
                sourceItem.setUserId(targetUserId);
                sourceItem.setUpdatedAt(Instant.now());
                requireSingleRow(cartItemMapper.updateById(sourceItem));
            } else {
                targetItem.setQuantity(saturatingQuantity(
                        targetItem.getQuantity(), sourceItem.getQuantity()));
                targetItem.setUpdatedAt(Instant.now());
                requireSingleRow(cartItemMapper.updateById(targetItem));
                requireSingleRow(cartItemMapper.deleteById(sourceItem.getId()));
            }
        }
    }

    private long saturatingQuantity(long left, long right) {
        if (left >= MAX_QUANTITY || right >= MAX_QUANTITY) {
            return MAX_QUANTITY;
        }
        if (left <= 0) {
            return Math.max(0, right);
        }
        if (right <= 0) {
            return left;
        }
        return left >= MAX_QUANTITY - right ? MAX_QUANTITY : left + right;
    }

    private void requireSingleRow(int changed) {
        if (changed != 1) {
            throw BusinessException.conflict("cart ownership merge conflicted");
        }
    }

    private CartItemResponse putLocked(long userId, CartItemRequest request) {
        ProductEntity product = productMapper.selectById(request.productId());
        if (product == null) {
            throw BusinessException.notFound("product not found");
        }
        if (!ON_SHELF.equals(product.getStatus())) {
            throw BusinessException.conflict("product is not on sale");
        }

        CartItemEntity item = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getProductId, request.productId())
                .last("limit 1"));
        boolean isNew = item == null;
        Instant now = Instant.now();
        if (isNew) {
            item = new CartItemEntity();
            item.setId(IdWorker.getId());
            item.setUserId(userId);
            item.setProductId(request.productId());
            item.setVersion(0L);
            item.setCreatedAt(now);
        }
        item.setQuantity(request.quantity());
        item.setUpdatedAt(now);
        int changed = isNew ? cartItemMapper.insert(item) : cartItemMapper.updateById(item);
        if (changed != 1) {
            throw BusinessException.conflict("cart item update conflicted");
        }
        return toResponse(item, product);
    }

    private CartItemResponse toResponse(CartItemEntity item, ProductEntity product) {
        if (product == null) {
            return new CartItemResponse(item.getId(), item.getProductId(), "", "", "", "", "",
                    BigDecimal.ZERO, 0L, item.getQuantity(), false, item.getUpdatedAt());
        }
        boolean available = ON_SHELF.equals(product.getStatus()) && product.getStock() >= item.getQuantity();
        return new CartItemResponse(item.getId(), item.getProductId(), blankIfNull(product.getSku()),
                blankIfNull(product.getName()), blankIfNull(product.getMainImage()), blankIfNull(product.getSpec()),
                blankIfNull(product.getUnit()), product.getPrice() == null ? BigDecimal.ZERO : product.getPrice(),
                product.getStock(), item.getQuantity(), available, item.getUpdatedAt());
    }

    private void validateProductId(long productId) {
        if (productId <= 0) {
            throw BusinessException.badRequest("productId must be positive");
        }
    }

    private void validateQuantity(long quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw BusinessException.badRequest("quantity must be between 1 and 99999");
        }
    }

    private String lockKey(long userId, long productId) {
        return "cart:" + userId + ":" + productId;
    }

    private String userLockKey(long userId) {
        return "cart:user:" + userId;
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private record CartMutationLock(long userId, long productId) {
    }

    private record CartCleanupSnapshot(long id,
                                       long userId,
                                       long productId,
                                       long quantity,
                                       long version,
                                       Instant updatedAt) {
        private static CartCleanupSnapshot from(CartItemEntity item) {
            return new CartCleanupSnapshot(item.getId(), item.getUserId(), item.getProductId(),
                    item.getQuantity(), item.getVersion(), item.getUpdatedAt());
        }
    }
}

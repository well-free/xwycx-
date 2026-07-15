package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.example.config.AppProperties;
import org.example.infrastructure.cache.CacheStore;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mq.OrderEventPublisher;
import org.example.infrastructure.mq.OrderTimeoutScheduler;
import org.example.infrastructure.rate.RateLimitService;
import org.example.infrastructure.mybatis.entity.OrderEntity;
import org.example.infrastructure.mybatis.entity.TradeEntity;
import org.example.infrastructure.mybatis.mapper.OrderMapper;
import org.example.infrastructure.mybatis.mapper.TradeMapper;
import org.example.trade.OrderSide;
import org.example.trade.OrderStatus;
import org.example.web.BusinessException;
import org.example.web.dto.OrderCreateRequest;
import org.example.web.dto.OrderPlacementResponse;
import org.example.web.dto.OrderResponse;
import org.example.web.dto.TradeResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class OrderCommandService {
    private final OrderMapper orderMapper;
    private final TradeMapper tradeMapper;
    private final DistributedLockService lockService;
    private final OrderEventPublisher eventPublisher;
    private final OrderTimeoutScheduler timeoutScheduler;
    private final CacheStore cacheService;
    private final RateLimitService rateLimitService;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public OrderCommandService(OrderMapper orderMapper,
                               TradeMapper tradeMapper,
                               DistributedLockService lockService,
                               OrderEventPublisher eventPublisher,
                               OrderTimeoutScheduler timeoutScheduler,
                               CacheStore cacheService,
                               RateLimitService rateLimitService,
                               AppProperties properties,
                               ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.tradeMapper = tradeMapper;
        this.lockService = lockService;
        this.eventPublisher = eventPublisher;
        this.timeoutScheduler = timeoutScheduler;
        this.cacheService = cacheService;
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public OrderPlacementResponse place(OrderCreateRequest request) {
        validate(request);
        String symbol = normalize(request.symbol());
        rateLimitService.acquire("order:create:" + symbol, 20L, 5L);
        return lockService.withLock("order:symbol:" + symbol, () -> {
            OrderEntity incoming = new OrderEntity();
            incoming.setId(IdWorker.getId());
            incoming.setSymbol(symbol);
            incoming.setSide(request.side().name());
            incoming.setPrice(request.price());
            incoming.setOriginalQuantity(request.quantity());
            incoming.setFilledQuantity(0L);
            incoming.setRemainingQuantity(request.quantity());
            incoming.setStatus(OrderStatus.NEW.name());
            incoming.setVersion(0L);
            Instant now = Instant.now();
            incoming.setCreatedAt(now);
            incoming.setUpdatedAt(now);
            orderMapper.insert(incoming);

            List<TradeResponse> trades = match(incoming);
            cacheOrder(incoming);

            if (isOpen(incoming)) {
                timeoutScheduler.schedule(incoming.getId(), Duration.ofSeconds(properties.getOrder().getTimeoutSeconds()));
                eventPublisher.sendTimeoutClose(incoming.getId(), Duration.ofSeconds(properties.getOrder().getTimeoutSeconds()));
            }
            return new OrderPlacementResponse(toResponse(incoming), trades);
        });
    }

    public OrderResponse cancel(long orderId) {
        return lockService.withLock("order:id:" + orderId, () -> {
            OrderEntity order = requireOrder(orderId);
            if (!isOpen(order)) {
                throw BusinessException.conflict("order is not open");
            }
            order.setStatus(OrderStatus.CANCELED.name());
            order.setUpdatedAt(Instant.now());
            persistOrderState(order);
            evictOrder(orderId);
            return toResponse(order);
        });
    }

    public void timeoutClose(long orderId) {
        lockService.withLock("order:id:" + orderId, () -> {
            OrderEntity order = requireOrder(orderId);
            if (isOpen(order)) {
                order.setStatus(OrderStatus.TIMEOUT_CLOSED.name());
                order.setUpdatedAt(Instant.now());
                persistOrderState(order);
                evictOrder(orderId);
            }
            return null;
        });
    }

    public OrderResponse get(long orderId) {
        String cacheKey = orderCacheKey(orderId);
        return cacheService.get(cacheKey)
                .map(this::readOrderResponse)
                .orElseGet(() -> {
                    OrderEntity order = requireOrder(orderId);
                    OrderResponse response = toResponse(order);
                    cacheOrder(order);
                    return response;
                });
    }

    public List<OrderResponse> listOrders() {
        return orderMapper.selectList(new LambdaQueryWrapper<OrderEntity>()
                        .orderByAsc(OrderEntity::getCreatedAt)
                        .orderByAsc(OrderEntity::getId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<TradeResponse> listTrades() {
        return tradeMapper.selectList(new LambdaQueryWrapper<TradeEntity>()
                        .orderByAsc(TradeEntity::getExecutedAt)
                        .orderByAsc(TradeEntity::getId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private List<TradeResponse> match(OrderEntity incoming) {
        boolean buy = OrderSide.BUY.name().equals(incoming.getSide());
        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getSymbol, incoming.getSymbol())
                .in(OrderEntity::getStatus, OrderStatus.NEW.name(), OrderStatus.PARTIALLY_FILLED.name())
                .orderByAsc(OrderEntity::getCreatedAt)
                .orderByAsc(OrderEntity::getId);
        if (buy) {
            wrapper.eq(OrderEntity::getSide, OrderSide.SELL.name())
                    .orderByAsc(OrderEntity::getPrice);
        } else {
            wrapper.eq(OrderEntity::getSide, OrderSide.BUY.name())
                    .orderByDesc(OrderEntity::getPrice);
        }

        List<OrderEntity> candidates = orderMapper.selectList(wrapper);
        if (!buy) {
            candidates = candidates.stream()
                    .sorted(Comparator.comparing(OrderEntity::getPrice).reversed()
                            .thenComparing(OrderEntity::getCreatedAt)
                            .thenComparing(OrderEntity::getId))
                    .toList();
        } else {
            candidates = candidates.stream()
                    .sorted(Comparator.comparing(OrderEntity::getPrice)
                            .thenComparing(OrderEntity::getCreatedAt)
                            .thenComparing(OrderEntity::getId))
                    .toList();
        }

        List<TradeResponse> trades = new java.util.ArrayList<>();
        for (OrderEntity candidate : candidates) {
            if (incoming.getRemainingQuantity() <= 0) {
                break;
            }
            if (!priceCrosses(incoming, candidate)) {
                break;
            }
            long quantity = Math.min(incoming.getRemainingQuantity(), candidate.getRemainingQuantity());
            executeTrade(incoming, candidate, quantity, trades);
        }
        return trades;
    }

    private void executeTrade(OrderEntity incoming, OrderEntity candidate, long quantity, List<TradeResponse> trades) {
        Instant now = Instant.now();
        incoming.setFilledQuantity(incoming.getFilledQuantity() + quantity);
        incoming.setRemainingQuantity(incoming.getRemainingQuantity() - quantity);
        incoming.setStatus(incoming.getRemainingQuantity() == 0 ? OrderStatus.FILLED.name() : OrderStatus.PARTIALLY_FILLED.name());
        incoming.setUpdatedAt(now);

        candidate.setFilledQuantity(candidate.getFilledQuantity() + quantity);
        candidate.setRemainingQuantity(candidate.getRemainingQuantity() - quantity);
        candidate.setStatus(candidate.getRemainingQuantity() == 0 ? OrderStatus.FILLED.name() : OrderStatus.PARTIALLY_FILLED.name());
        candidate.setUpdatedAt(now);

        persistOrderState(incoming);
        persistOrderState(candidate);

        TradeEntity trade = new TradeEntity();
        trade.setId(IdWorker.getId());
        trade.setOrderId(incoming.getId());
        trade.setSymbol(incoming.getSymbol());
        trade.setBuyOrderId(OrderSide.BUY.name().equals(incoming.getSide()) ? incoming.getId() : candidate.getId());
        trade.setSellOrderId(OrderSide.SELL.name().equals(incoming.getSide()) ? incoming.getId() : candidate.getId());
        trade.setPrice(candidate.getPrice());
        trade.setQuantity(quantity);
        trade.setExecutedAt(now);
        tradeMapper.insert(trade);
        trades.add(toResponse(trade));
        evictOrder(candidate.getId());
    }

    private boolean priceCrosses(OrderEntity incoming, OrderEntity candidate) {
        BigDecimal buyPrice = OrderSide.BUY.name().equals(incoming.getSide()) ? incoming.getPrice() : candidate.getPrice();
        BigDecimal sellPrice = OrderSide.SELL.name().equals(incoming.getSide()) ? incoming.getPrice() : candidate.getPrice();
        return buyPrice.compareTo(sellPrice) >= 0;
    }

    private void cacheOrder(OrderEntity order) {
        try {
            cacheService.put(orderCacheKey(order.getId()), objectMapper.writeValueAsString(toResponse(order)),
                    Duration.ofSeconds(properties.getOrder().getCacheTtlSeconds()));
        } catch (JacksonException e) {
            throw new IllegalStateException("failed to cache order", e);
        }
    }

    private void evictOrder(long orderId) {
        cacheService.evict(orderCacheKey(orderId));
    }

    private OrderEntity requireOrder(long orderId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("order not found");
        }
        return order;
    }

    private void persistOrderState(OrderEntity order) {
        int updated = orderMapper.updateById(order);
        if (updated == 0) {
            throw BusinessException.conflict("order update conflicted");
        }
    }

    private void validate(OrderCreateRequest request) {
        if (request == null || request.symbol() == null || request.symbol().isBlank()) {
            throw BusinessException.badRequest("symbol is required");
        }
        if (request.side() == null) {
            throw BusinessException.badRequest("side is required");
        }
        if (request.price() == null || request.price().compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("price must be positive");
        }
        if (request.quantity() <= 0) {
            throw BusinessException.badRequest("quantity must be positive");
        }
    }

    private boolean isOpen(OrderEntity order) {
        return OrderStatus.NEW.name().equals(order.getStatus()) || OrderStatus.PARTIALLY_FILLED.name().equals(order.getStatus());
    }

    private String normalize(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String orderCacheKey(long orderId) {
        return "order:" + orderId;
    }

    private OrderResponse readOrderResponse(String json) {
        try {
            return objectMapper.readValue(json, OrderResponse.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("failed to read cached order", e);
        }
    }

    private OrderResponse toResponse(OrderEntity order) {
        return new OrderResponse(order.getId(), order.getSymbol(), OrderSide.valueOf(order.getSide()), order.getPrice(),
                order.getOriginalQuantity(), order.getFilledQuantity(), order.getRemainingQuantity(),
                OrderStatus.valueOf(order.getStatus()), order.getCreatedAt(), order.getUpdatedAt());
    }

    private TradeResponse toResponse(TradeEntity trade) {
        return new TradeResponse(trade.getId(), trade.getSymbol(), trade.getBuyOrderId(), trade.getSellOrderId(),
                trade.getPrice(), trade.getQuantity(), trade.getExecutedAt());
    }
}

package org.example.trade;

import java.math.BigDecimal;
import java.time.Instant;

public final class Order {
    private final long id;
    private final String symbol;
    private final OrderSide side;
    private final BigDecimal price;
    private final long originalQuantity;
    private final Instant createdAt;
    private Instant updatedAt;
    private long filledQuantity;
    private long remainingQuantity;
    private OrderStatus status;

    public Order(long id, String symbol, OrderSide side, BigDecimal price, long quantity, Instant createdAt) {
        this.id = id;
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.originalQuantity = quantity;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.remainingQuantity = quantity;
        this.status = OrderStatus.NEW;
    }

    public long getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public OrderSide getSide() {
        return side;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getOriginalQuantity() {
        return originalQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getFilledQuantity() {
        return filledQuantity;
    }

    public long getRemainingQuantity() {
        return remainingQuantity;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public boolean isOpen() {
        return status == OrderStatus.NEW || status == OrderStatus.PARTIALLY_FILLED;
    }

    public void fill(long quantity, Instant timestamp) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("fill quantity must be positive");
        }
        if (quantity > remainingQuantity) {
            throw new IllegalArgumentException("fill quantity exceeds remaining quantity");
        }
        remainingQuantity -= quantity;
        filledQuantity += quantity;
        updatedAt = timestamp;
        status = remainingQuantity == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void cancel(Instant timestamp) {
        if (!isOpen()) {
            throw new IllegalStateException("order is not open");
        }
        status = OrderStatus.CANCELED;
        updatedAt = timestamp;
    }
}

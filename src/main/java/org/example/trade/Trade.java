package org.example.trade;

import java.math.BigDecimal;
import java.time.Instant;

public final class Trade {
    private final long id;
    private final String symbol;
    private final long buyOrderId;
    private final long sellOrderId;
    private final BigDecimal price;
    private final long quantity;
    private final Instant executedAt;

    public Trade(long id, String symbol, long buyOrderId, long sellOrderId, BigDecimal price, long quantity, Instant executedAt) {
        this.id = id;
        this.symbol = symbol;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.price = price;
        this.quantity = quantity;
        this.executedAt = executedAt;
    }

    public long getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public long getBuyOrderId() {
        return buyOrderId;
    }

    public long getSellOrderId() {
        return sellOrderId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }
}

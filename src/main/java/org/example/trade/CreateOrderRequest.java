package org.example.trade;

import java.math.BigDecimal;

public final class CreateOrderRequest {
    private final String symbol;
    private final OrderSide side;
    private final BigDecimal price;
    private final long quantity;

    public CreateOrderRequest(String symbol, OrderSide side, BigDecimal price, long quantity) {
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
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

    public long getQuantity() {
        return quantity;
    }
}

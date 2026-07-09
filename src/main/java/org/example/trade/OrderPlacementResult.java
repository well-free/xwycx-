package org.example.trade;

import java.util.List;

public final class OrderPlacementResult {
    private final Order order;
    private final List<Trade> trades;

    public OrderPlacementResult(Order order, List<Trade> trades) {
        this.order = order;
        this.trades = trades;
    }

    public Order getOrder() {
        return order;
    }

    public List<Trade> getTrades() {
        return trades;
    }
}

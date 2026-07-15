package org.example.web.dto;

import org.example.trade.OrderSide;
import org.example.trade.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        long id,
        String symbol,
        OrderSide side,
        BigDecimal price,
        long originalQuantity,
        long filledQuantity,
        long remainingQuantity,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt) {
}

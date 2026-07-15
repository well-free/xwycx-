package org.example.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeResponse(
        long id,
        String symbol,
        long buyOrderId,
        long sellOrderId,
        BigDecimal price,
        long quantity,
        Instant executedAt) {
}

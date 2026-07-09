package org.example.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        long id,
        long merchantId,
        String name,
        BigDecimal price,
        long stock,
        int hotScore,
        Instant updatedAt) {
}

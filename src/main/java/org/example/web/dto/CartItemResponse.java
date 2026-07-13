package org.example.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CartItemResponse(
        long id,
        long productId,
        String sku,
        String name,
        String mainImage,
        String spec,
        String unit,
        BigDecimal currentPrice,
        long currentStock,
        long quantity,
        boolean available,
        Instant updatedAt) {
}

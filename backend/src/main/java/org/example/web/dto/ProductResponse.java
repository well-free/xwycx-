package org.example.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        long id,
        long merchantId,
        String sku,
        String name,
        BigDecimal price,
        long stock,
        int hotScore,
        String mainImage,
        String detailImages,
        String spec,
        String unit,
        String status,
        int sortOrder,
        Instant updatedAt,
        long reservedStock,
        long soldStock) {
}

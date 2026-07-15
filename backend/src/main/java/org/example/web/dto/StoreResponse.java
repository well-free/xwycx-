package org.example.web.dto;

import java.time.Instant;

public record StoreResponse(
        long id,
        String storeName,
        String logoUrl,
        String customerServicePhone,
        String shippingAddress,
        String refundAddress,
        String businessStatus,
        Instant updatedAt) {
}

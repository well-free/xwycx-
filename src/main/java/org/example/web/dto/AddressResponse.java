package org.example.web.dto;

import java.time.Instant;

public record AddressResponse(
        long id,
        long userId,
        String receiverName,
        String receiverPhone,
        String province,
        String city,
        String district,
        String detail,
        boolean defaultAddress,
        Instant createdAt,
        Instant updatedAt) {
}

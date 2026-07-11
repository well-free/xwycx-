package org.example.web.dto;

import java.math.BigDecimal;

public record CustomerOrderItemResponse(
        long id,
        long productId,
        String sku,
        String productName,
        BigDecimal unitPrice,
        long quantity,
        BigDecimal subtotal) {
}

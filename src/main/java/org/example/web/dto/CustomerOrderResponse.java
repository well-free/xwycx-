package org.example.web.dto;

import org.example.commerce.CustomerOrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CustomerOrderResponse(
        long id,
        String orderNo,
        long userId,
        long addressId,
        ShippingAddressSnapshot shippingAddress,
        BigDecimal totalAmount,
        CustomerOrderStatus status,
        String remark,
        List<CustomerOrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt,
        Instant paidAt,
        Instant shippedAt,
        Instant canceledAt) {
}

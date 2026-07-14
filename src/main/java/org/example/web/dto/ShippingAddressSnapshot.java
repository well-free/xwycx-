package org.example.web.dto;

public record ShippingAddressSnapshot(
        String receiverName,
        String receiverPhone,
        String province,
        String city,
        String district,
        String detail) {
}

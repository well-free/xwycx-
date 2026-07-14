package org.example.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record CustomerOrderCreateRequest(
        @NotEmpty List<@Valid CustomerOrderItemRequest> items,
        @Positive long addressId,
        String remark) {
}

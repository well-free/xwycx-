package org.example.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

public record CustomerOrderItemRequest(
        @Positive long productId,
        @Min(1) long quantity) {
}

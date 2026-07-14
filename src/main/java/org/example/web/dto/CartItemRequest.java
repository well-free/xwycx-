package org.example.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

public record CartItemRequest(
        @Positive long productId,
        @Min(1) @Max(99999) long quantity) {
}

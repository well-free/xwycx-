package org.example.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record RefundCreateRequest(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        String reason) {
}

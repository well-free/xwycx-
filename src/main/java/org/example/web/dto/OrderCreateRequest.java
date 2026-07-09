package org.example.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.example.trade.OrderSide;

import java.math.BigDecimal;

public record OrderCreateRequest(
        @NotBlank String symbol,
        @NotNull OrderSide side,
        @NotNull @DecimalMin(value = "0.01") BigDecimal price,
        @Positive long quantity) {
}

package org.example.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PaymentCallbackRequest(
        @Positive long paymentId,
        @NotBlank String notifyId,
        @NotBlank String channelTradeNo,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank String status,
        @NotBlank String signature) {
}

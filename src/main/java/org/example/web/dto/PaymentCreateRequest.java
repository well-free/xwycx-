package org.example.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.example.payment.PaymentChannel;

public record PaymentCreateRequest(
        @Positive long orderId,
        @NotNull PaymentChannel channel) {
}

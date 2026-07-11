package org.example.web.dto;

import jakarta.validation.constraints.NotBlank;

public record SmsLoginRequest(@NotBlank String phone, @NotBlank String code) {
}

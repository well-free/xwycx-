package org.example.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SmsLoginRequest(@NotBlank String phone, @NotBlank @Size(max = 16) String code) {
}

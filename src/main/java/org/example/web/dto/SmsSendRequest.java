package org.example.web.dto;

import jakarta.validation.constraints.NotBlank;

public record SmsSendRequest(@NotBlank String phone) {
}

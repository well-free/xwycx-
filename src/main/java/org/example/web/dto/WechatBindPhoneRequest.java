package org.example.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WechatBindPhoneRequest(
        @NotBlank @Pattern(regexp = "^1\\d{10}$") String phone,
        @NotBlank @Size(max = 16) String code) {
}

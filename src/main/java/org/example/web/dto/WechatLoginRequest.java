package org.example.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WechatLoginRequest(@NotBlank @Size(max = 128) String code) {
}

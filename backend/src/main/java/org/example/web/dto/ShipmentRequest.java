package org.example.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShipmentRequest(
        @NotBlank @Size(max = 64) String carrier,
        @NotBlank @Size(max = 128) String trackingNo) {
}

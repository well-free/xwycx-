package org.example.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record ProductUpsertRequest(
        @NotBlank String sku,
        @NotBlank String name,
        @DecimalMin("0.01") BigDecimal price,
        @Min(0) long stock,
        String mainImage,
        String detailImages,
        String spec,
        String unit,
        String status,
        int sortOrder) {
}

package org.example.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CartQuantityRequest(
        @Min(1) @Max(99999) long quantity) {
}

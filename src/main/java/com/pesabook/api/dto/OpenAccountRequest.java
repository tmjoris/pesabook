package com.pesabook.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OpenAccountRequest(
        @NotBlank(message = "reference is required")
        String reference,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a three letter ISO code")
        String currency) {
}

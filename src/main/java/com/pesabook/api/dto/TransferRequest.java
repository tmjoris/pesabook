package com.pesabook.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record TransferRequest(
        @NotNull(message = "sourceAccount is required")
        UUID sourceAccount,

        @NotNull(message = "targetAccount is required")
        UUID targetAccount,

        /**
         * The amount in the currency's smallest unit. Money is never held in a
         * floating point type here, because a value that cannot represent 0.10
         * exactly has no business in a ledger.
         */
        @Positive(message = "amountMinor must be a positive number of minor units")
        long amountMinor,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a three letter ISO code")
        String currency) {
}

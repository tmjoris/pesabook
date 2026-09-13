package com.pesabook.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Brings money into the ledger from outside it.
 *
 * In double entry money cannot simply appear, so this posts a movement from an
 * account standing for the world beyond this service. That account runs
 * negative by design, and its balance is how much the ledger owes outward.
 */
public record FundingRequest(
        @Positive(message = "amountMinor must be a positive number of minor units")
        long amountMinor,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a three letter ISO code")
        String currency) {
}

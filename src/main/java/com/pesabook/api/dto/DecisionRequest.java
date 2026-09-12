package com.pesabook.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * A reviewer's verdict on a transfer that was held.
 *
 * The reason is required for a refusal and optional for an approval, because
 * the useful thing to record is why someone overrode a machine's caution or
 * confirmed it.
 */
public record DecisionRequest(
        @NotNull(message = "decision is required")
        @Pattern(regexp = "^(APPROVE|REFUSE)$", message = "decision must be APPROVE or REFUSE")
        String decision,

        String reason) {

    public boolean isApproval() {
        return "APPROVE".equals(decision);
    }
}

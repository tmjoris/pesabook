package com.pesabook.risk;

import java.util.UUID;

/**
 * What a rule is given to judge. Deliberately just the facts of the attempt,
 * so a rule can be tested without a database.
 */
public record RiskContext(UUID sourceAccount,
                          UUID targetAccount,
                          long amountMinor,
                          String currency) {
}

package com.pesabook.risk;

/**
 * One reason a transfer was held or refused.
 *
 * The reason is kept separate from the decision because telling an investigator
 * that something was held is far less useful than telling them why.
 */
public record RiskSignal(String rule, RiskDecision decision, String detail) {

    public static RiskSignal allow(String rule) {
        return new RiskSignal(rule, RiskDecision.ALLOW, "no concern");
    }
}

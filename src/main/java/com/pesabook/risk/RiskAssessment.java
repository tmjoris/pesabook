package com.pesabook.risk;

import java.util.List;

/**
 * The combined verdict and the signals that produced it.
 */
public record RiskAssessment(RiskDecision decision, List<RiskSignal> signals) {

    public boolean allowsPosting() {
        return decision == RiskDecision.ALLOW;
    }

    /**
     * The signals that actually drove the outcome, which is what belongs in a
     * response or an alert. The ones that found nothing are noise.
     */
    public List<RiskSignal> significantSignals() {
        return signals.stream()
                .filter(s -> s.decision() != RiskDecision.ALLOW)
                .toList();
    }
}

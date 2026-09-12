package com.pesabook.risk;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Runs every rule and takes the strictest answer.
 *
 * Every rule runs even once one has said BLOCK, because the point of the
 * signals is to explain the decision to whoever reviews it, and knowing that
 * three rules objected is different from knowing that one did.
 */
@Service
public class RiskEngine {

    private final List<RiskRule> rules;

    public RiskEngine(List<RiskRule> rules) {
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public RiskAssessment assess(RiskContext context) {
        List<RiskSignal> signals = rules.stream()
                .map(rule -> rule.evaluate(context))
                .toList();

        RiskDecision decision = signals.stream()
                .map(RiskSignal::decision)
                .reduce(RiskDecision.ALLOW, RiskDecision::strictest);

        return new RiskAssessment(decision, signals);
    }
}

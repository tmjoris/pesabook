package com.pesabook.risk;

/**
 * What the risk check decided before any money moved.
 *
 * Ordered from least to most restrictive so that combining several rules is
 * just taking the maximum.
 */
public enum RiskDecision {
    ALLOW,
    REVIEW,
    BLOCK;

    public RiskDecision strictest(RiskDecision other) {
        return this.compareTo(other) >= 0 ? this : other;
    }
}

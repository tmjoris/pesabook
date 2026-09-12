package com.pesabook.risk

import spock.lang.Specification

class RiskDecisionSpec extends Specification {

    def "combining decisions keeps the strictest"() {
        expect:
        a.strictest(b) == expected

        where:
        a                   | b                   || expected
        RiskDecision.ALLOW  | RiskDecision.ALLOW  || RiskDecision.ALLOW
        RiskDecision.ALLOW  | RiskDecision.REVIEW || RiskDecision.REVIEW
        RiskDecision.REVIEW | RiskDecision.ALLOW  || RiskDecision.REVIEW
        RiskDecision.REVIEW | RiskDecision.BLOCK  || RiskDecision.BLOCK
        RiskDecision.BLOCK  | RiskDecision.ALLOW  || RiskDecision.BLOCK
        RiskDecision.BLOCK  | RiskDecision.BLOCK  || RiskDecision.BLOCK
    }

    def "combining is order independent"() {
        expect: "so the order rules happen to run in cannot change the outcome"
        a.strictest(b) == b.strictest(a)

        where:
        [a, b] << [RiskDecision.values(), RiskDecision.values()].combinations()
    }
}

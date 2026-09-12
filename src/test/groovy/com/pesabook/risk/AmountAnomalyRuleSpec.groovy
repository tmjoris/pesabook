package com.pesabook.risk

import com.pesabook.ledger.TransferRepository
import spock.lang.Specification
import spock.lang.Subject

class AmountAnomalyRuleSpec extends Specification {

    TransferRepository transfers = Mock()

    // Ceiling of 5,000,000 minor units, and ten times the recent median.
    @Subject
    AmountAnomalyRule rule = new AmountAnomalyRule(transfers, 5_000_000, 10)

    private static RiskContext of(long amount) {
        new RiskContext(UUID.randomUUID(), UUID.randomUUID(), amount, 'KES')
    }

    def "reviews anything at or above the flat ceiling regardless of history"() {
        given: "history that would otherwise make this look ordinary"
        transfers.recentAmountsFromAccount(_, _) >> [5_000_000L] * 20

        expect:
        rule.evaluate(of(5_000_000)).decision() == RiskDecision.REVIEW
    }

    def "allows a first few transfers because there is no normal to compare to"() {
        given: "fewer than the five needed to establish a pattern"
        transfers.recentAmountsFromAccount(_, _) >> [100L, 200L, 300L, 400L]

        expect: "flagging here would flag every new account"
        rule.evaluate(of(900_000)).decision() == RiskDecision.ALLOW
    }

    def "reviews an amount far above the account's own median"() {
        given: "a median of 1000"
        transfers.recentAmountsFromAccount(_, _) >> [900L, 1000L, 1000L, 1100L, 1000L]

        expect:
        rule.evaluate(of(amount)).decision() == expected

        where:
        amount  || expected
        1_000   || RiskDecision.ALLOW
        9_999   || RiskDecision.ALLOW
        10_000  || RiskDecision.ALLOW
        10_001  || RiskDecision.REVIEW
        500_000 || RiskDecision.REVIEW
    }

    def "uses the median so one past outlier cannot mask the next one"() {
        given: "nine small transfers and one very large one"
        def history = [100L, 100L, 100L, 100L, 100L, 100L, 100L, 100L, 100L, 1_000_000L]
        transfers.recentAmountsFromAccount(_, _) >> history

        when: "an amount that a mean of about 100,090 would wave through"
        def signal = rule.evaluate(of(50_000))

        then: "the median of 100 still sees it as out of character"
        signal.decision() == RiskDecision.REVIEW
        signal.detail().contains('median of 100')
    }

    def "handles an even sized history by averaging the middle two"() {
        given:
        transfers.recentAmountsFromAccount(_, _) >> [100L, 200L, 300L, 400L, 500L, 600L]

        expect: "median is 350, so the trigger sits just above 3500"
        rule.evaluate(of(3_500)).decision() == RiskDecision.ALLOW
        rule.evaluate(of(3_501)).decision() == RiskDecision.REVIEW
    }

    def "does not divide by zero when the history is all zeroes"() {
        given:
        transfers.recentAmountsFromAccount(_, _) >> [0L, 0L, 0L, 0L, 0L]

        when:
        def signal = rule.evaluate(of(1_000))

        then:
        noExceptionThrown()
        signal.decision() == RiskDecision.ALLOW
    }
}

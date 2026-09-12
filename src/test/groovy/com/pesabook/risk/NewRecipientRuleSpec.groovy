package com.pesabook.risk

import com.pesabook.ledger.TransferRepository
import spock.lang.Specification
import spock.lang.Subject

/**
 * The 2024 FinAccess Household Survey found that among Kenyans who lost money
 * through mobile money, 70.0 percent lost it by sending to the wrong recipient.
 * This rule is aimed at that case.
 */
class NewRecipientRuleSpec extends Specification {

    TransferRepository transfers = Mock()

    @Subject
    NewRecipientRule rule = new NewRecipientRule(transfers, 1_000_000)

    private static RiskContext of(long amount) {
        new RiskContext(UUID.randomUUID(), UUID.randomUUID(), amount, 'KES')
    }

    def "reviews a large first payment to someone never paid before"() {
        given:
        transfers.countPostedBetween(_, _) >> 0

        when:
        def signal = rule.evaluate(of(1_000_000))

        then:
        signal.decision() == RiskDecision.REVIEW
        signal.detail().contains('first payment')
    }

    def "allows a large payment to a recipient already paid before"() {
        given:
        transfers.countPostedBetween(_, _) >> 3

        expect: "an established relationship is the ordinary case"
        rule.evaluate(of(5_000_000)).decision() == RiskDecision.ALLOW
    }

    def "ignores small first payments so the warning keeps its meaning"() {
        given: "a brand new recipient"
        transfers.countPostedBetween(_, _) >> 0

        expect: "flagging every first payment would train people to dismiss it"
        rule.evaluate(of(amount)).decision() == expected

        where:
        amount    || expected
        1         || RiskDecision.ALLOW
        999_999   || RiskDecision.ALLOW
        1_000_000 || RiskDecision.REVIEW
        9_000_000 || RiskDecision.REVIEW
    }

    def "does not query history for a small amount"() {
        when:
        rule.evaluate(of(500))

        then: "below the threshold the answer cannot change, so do not ask"
        0 * transfers.countPostedBetween(_, _)
    }
}

package com.pesabook.risk

import com.pesabook.ledger.TransferRepository
import spock.lang.Specification
import spock.lang.Subject

import java.time.Instant

class VelocityRuleSpec extends Specification {

    TransferRepository transfers = Mock()

    @Subject
    VelocityRule rule = new VelocityRule(transfers, 10, 5, 10)

    private static RiskContext context() {
        new RiskContext(UUID.randomUUID(), UUID.randomUUID(), 10_000, 'KES')
    }

    def "allows an account that has been quiet"() {
        given:
        transfers.countPostedFromAccountSince(_, _) >> 0

        expect:
        rule.evaluate(context()).decision() == RiskDecision.ALLOW
    }

    def "counts the attempt being judged, not just the ones already posted"() {
        given: "four already posted, so this one would be the fifth"
        transfers.countPostedFromAccountSince(_, _) >> 4

        expect: "the review threshold of five is reached by the attempt itself"
        rule.evaluate(context()).decision() == RiskDecision.REVIEW
    }

    def "escalates from review to block as the burst grows"() {
        given:
        transfers.countPostedFromAccountSince(_, _) >> alreadyPosted

        expect:
        rule.evaluate(context()).decision() == expected

        where:
        alreadyPosted || expected
        0             || RiskDecision.ALLOW
        3             || RiskDecision.ALLOW
        4             || RiskDecision.REVIEW
        8             || RiskDecision.REVIEW
        9             || RiskDecision.BLOCK
        50            || RiskDecision.BLOCK
    }

    def "looks only at the configured window"() {
        given:
        Instant asked = null
        transfers.countPostedFromAccountSince(_, _) >> { args -> asked = args[1]; 0 }

        when:
        rule.evaluate(context())

        then: "roughly ten minutes ago, allowing for the clock moving during the test"
        def agoSeconds = Instant.now().epochSecond - asked.epochSecond
        agoSeconds >= 598 && agoSeconds <= 602
    }

    def "explains itself when it objects"() {
        given:
        transfers.countPostedFromAccountSince(_, _) >> 9

        when:
        def signal = rule.evaluate(context())

        then:
        signal.rule() == 'velocity'
        signal.detail().contains('10 transfers')
        signal.detail().contains('10 minutes')
    }
}

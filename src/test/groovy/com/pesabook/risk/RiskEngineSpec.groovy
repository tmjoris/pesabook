package com.pesabook.risk

import spock.lang.Specification
import spock.lang.Subject

class RiskEngineSpec extends Specification {

    private static RiskRule ruleReturning(String name, RiskDecision decision) {
        new RiskRule() {
            String name() { name }
            RiskSignal evaluate(RiskContext c) { new RiskSignal(name, decision, 'because') }
        }
    }

    private static RiskContext context() {
        new RiskContext(UUID.randomUUID(), UUID.randomUUID(), 1_000, 'KES')
    }

    def "allows only when every rule allows"() {
        given:
        @Subject
        def engine = new RiskEngine([
                ruleReturning('a', RiskDecision.ALLOW),
                ruleReturning('b', RiskDecision.ALLOW)])

        expect:
        engine.assess(context()).decision() == RiskDecision.ALLOW
        engine.assess(context()).allowsPosting()
    }

    def "takes the strictest decision any rule reached"() {
        given:
        def engine = new RiskEngine([
                ruleReturning('a', RiskDecision.ALLOW),
                ruleReturning('b', RiskDecision.REVIEW),
                ruleReturning('c', RiskDecision.BLOCK)])

        expect:
        engine.assess(context()).decision() == RiskDecision.BLOCK
    }

    def "runs every rule even after one has already blocked"() {
        given: "a rule that records whether it was consulted"
        def consulted = []
        def watcher = new RiskRule() {
            String name() { 'watcher' }
            RiskSignal evaluate(RiskContext c) {
                consulted << 'watcher'
                RiskSignal.allow('watcher')
            }
        }
        def engine = new RiskEngine([ruleReturning('blocker', RiskDecision.BLOCK), watcher])

        when:
        engine.assess(context())

        then: "a reviewer wants every objection, not just the first"
        consulted == ['watcher']
    }

    def "reports only the signals that objected"() {
        given:
        def engine = new RiskEngine([
                ruleReturning('quiet', RiskDecision.ALLOW),
                ruleReturning('loud', RiskDecision.REVIEW)])

        when:
        def assessment = engine.assess(context())

        then:
        assessment.signals().size() == 2
        assessment.significantSignals()*.rule() == ['loud']
    }

    def "allows when there are no rules at all"() {
        expect: "an empty rule set is not a reason to refuse payments"
        new RiskEngine([]).assess(context()).decision() == RiskDecision.ALLOW
    }
}

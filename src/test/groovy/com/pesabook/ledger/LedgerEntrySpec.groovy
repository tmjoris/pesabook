package com.pesabook.ledger

import spock.lang.Specification

import java.util.UUID

/**
 * The rule the whole design rests on: a transfer's entries sum to zero.
 * If that ever stops holding, money has been created or destroyed.
 */
class LedgerEntrySpec extends Specification {

    def "a credit contributes positively and a debit negatively"() {
        given:
        def transfer = UUID.randomUUID()
        def account = UUID.randomUUID()

        expect:
        new LedgerEntry(transfer, account, Direction.CREDIT, 500, 'KES').signedAmount() == 500
        new LedgerEntry(transfer, account, Direction.DEBIT, 500, 'KES').signedAmount() == -500
    }

    def "a matched pair sums to zero"() {
        given:
        def transfer = UUID.randomUUID()
        def debit = new LedgerEntry(transfer, UUID.randomUUID(), Direction.DEBIT, amount, 'KES')
        def credit = new LedgerEntry(transfer, UUID.randomUUID(), Direction.CREDIT, amount, 'KES')

        expect:
        debit.signedAmount() + credit.signedAmount() == 0

        where:
        amount << [1, 100, 72_870_000_000, Long.MAX_VALUE / 2 as long]
    }

    def "an entry refuses a non positive amount"() {
        when: "a zero or negative entry is attempted"
        new LedgerEntry(UUID.randomUUID(), UUID.randomUUID(), Direction.DEBIT, amount, 'KES')

        then: "direction carries the sign, so the amount itself must be positive"
        thrown(IllegalArgumentException)

        where:
        amount << [0, -1, -500]
    }

    def "an entry exposes no setter, so it cannot be edited after the fact"() {
        given:
        def methods = LedgerEntry.declaredMethods*.name

        expect: "corrections are posted as new entries rather than by editing old ones"
        methods.every { !it.startsWith('set') }
    }
}

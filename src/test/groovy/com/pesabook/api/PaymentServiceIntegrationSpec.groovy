package com.pesabook.api

import com.pesabook.ledger.Account
import com.pesabook.ledger.LedgerService
import com.pesabook.ledger.TransferStatus
import com.pesabook.risk.RiskDecision
import com.pesabook.support.ContainerSpec
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

import com.pesabook.api.dto.TransferRequest

/**
 * The risk check and the ledger together, against a real database.
 *
 * Thresholds are lowered here so a spec does not have to post fifty transfers
 * to reach one.
 */
@SpringBootTest
@TestPropertySource(properties = [
        'pesabook.risk.velocity.window-minutes=10',
        'pesabook.risk.velocity.review-threshold=3',
        'pesabook.risk.velocity.block-threshold=5',
        'pesabook.risk.amount.review-minor=1000000',
        'pesabook.risk.amount.multiple-of-median=10',
        'pesabook.risk.new-recipient.review-minor=500000'
])
class PaymentServiceIntegrationSpec extends ContainerSpec {

    @Autowired
    PaymentService payments

    @Autowired
    LedgerService ledger

    private Account account(String reference) {
        ledger.openAccount(reference + '-' + UUID.randomUUID(), 'KES')
    }

    private Account funded(String reference, long amount) {
        def target = account(reference)
        ledger.fundFromExternal(target.id, amount, 'KES')
        target
    }

    def "an ordinary transfer is allowed and posted"() {
        given:
        def alice = funded('alice', 100_000)
        def bob = account('bob')

        when:
        def response = payments.transfer(new TransferRequest(alice.id, bob.id, 1_000, 'KES'))

        then:
        response.status() == TransferStatus.POSTED.name()
        response.riskDecision() == RiskDecision.ALLOW.name()
        ledger.balanceOf(bob.id) == 1_000
    }

    def "a large amount is held for review and no money moves"() {
        given:
        def alice = funded('alice', 5_000_000)
        def bob = account('bob')

        when: "an amount at the configured ceiling"
        def response = payments.transfer(new TransferRequest(alice.id, bob.id, 1_000_000, 'KES'))

        then:
        response.status() == TransferStatus.HELD_FOR_REVIEW.name()
        response.riskDecision() == RiskDecision.REVIEW.name()

        and: "the balances are untouched"
        ledger.balanceOf(alice.id) == 5_000_000
        ledger.balanceOf(bob.id) == 0

        and: "but the attempt is on record, with a reason"
        !response.riskSignals().isEmpty()
        response.riskSignals()*.rule().contains('amount-anomaly')
    }

    def "a burst of transfers is eventually blocked"() {
        given:
        def alice = funded('alice', 100_000)
        def bob = account('bob')

        when: "the same account sends repeatedly in a short window"
        def outcomes = (1..6).collect {
            payments.transfer(new TransferRequest(alice.id, bob.id, 100, 'KES')).status()
        }

        then: "the first few post, then review, then block"
        outcomes[0] == TransferStatus.POSTED.name()
        outcomes[1] == TransferStatus.POSTED.name()
        outcomes.contains(TransferStatus.HELD_FOR_REVIEW.name())
        outcomes.last() == TransferStatus.BLOCKED.name()
    }

    def "a first large payment to a new recipient is held"() {
        given:
        def alice = funded('alice', 900_000)
        def stranger = account('stranger')

        when:
        def response = payments.transfer(new TransferRequest(alice.id, stranger.id, 600_000, 'KES'))

        then:
        response.riskDecision() == RiskDecision.REVIEW.name()
        response.riskSignals()*.rule().contains('new-recipient')
        ledger.balanceOf(stranger.id) == 0
    }

    def "a blocked transfer is still visible afterwards"() {
        given:
        def alice = funded('alice', 100_000)
        def bob = account('bob')
        (1..5).each { payments.transfer(new TransferRequest(alice.id, bob.id, 100, 'KES')) }

        when:
        def blocked = payments.transfer(new TransferRequest(alice.id, bob.id, 100, 'KES'))

        then: "a refused attempt is one of the more interesting things in a history"
        blocked.status() == TransferStatus.BLOCKED.name()
        blocked.id() != null
        ledger.entriesFor(blocked.id()).isEmpty()
    }

    def "reversing through the service restores the balances"() {
        given:
        def alice = funded('alice', 50_000)
        def bob = account('bob')
        def posted = payments.transfer(new TransferRequest(alice.id, bob.id, 3_000, 'KES'))

        when:
        def reversal = payments.reverse(posted.id())

        then:
        reversal.reverses() == posted.id()
        ledger.balanceOf(alice.id) == 50_000
        ledger.balanceOf(bob.id) == 0
        ledger.totalOfAllEntries() == 0
    }
}

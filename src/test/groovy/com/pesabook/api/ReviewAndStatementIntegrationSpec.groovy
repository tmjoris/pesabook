package com.pesabook.api

import com.pesabook.api.dto.DecisionRequest
import com.pesabook.api.dto.TransferRequest
import com.pesabook.ledger.Account
import com.pesabook.ledger.LedgerException
import com.pesabook.ledger.LedgerService
import com.pesabook.ledger.TransferStatus
import com.pesabook.risk.RiskDecision
import com.pesabook.support.ContainerSpec
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

/**
 * The read side, and the path out of a held transfer.
 *
 * Without these the ledger is auditable in principle and not in practice, and
 * anything the risk check holds sits there forever.
 */
@SpringBootTest
@TestPropertySource(properties = [
        'pesabook.risk.velocity.window-minutes=10',
        'pesabook.risk.velocity.review-threshold=50',
        'pesabook.risk.velocity.block-threshold=99',
        'pesabook.risk.amount.review-minor=1000000',
        'pesabook.risk.amount.multiple-of-median=10',
        'pesabook.risk.new-recipient.review-minor=500000'
])
class ReviewAndStatementIntegrationSpec extends ContainerSpec {

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

    private static TransferRequest heldTransfer(Account from, Account to) {
        // At the configured ceiling, so the amount rule holds it.
        new TransferRequest(from.id, to.id, 1_000_000, 'KES')
    }

    def "a statement shows every entry with a running balance"() {
        given:
        def alice = funded('alice', 10_000)
        def bob = account('bob')
        payments.transfer(new TransferRequest(alice.id, bob.id, 3_000, 'KES'))
        payments.transfer(new TransferRequest(alice.id, bob.id, 2_000, 'KES'))

        when:
        def statement = payments.statement(alice.id)

        then: "the funding credit, then the two debits"
        statement.lines().size() == 3
        statement.lines()*.runningBalanceMinor() == [10_000, 7_000, 5_000]

        and: "and the closing balance agrees with the account balance"
        statement.balanceMinor() == ledger.balanceOf(alice.id)
        statement.balanceMinor() == 5_000
    }

    def "a statement on an account with no activity is empty rather than an error"() {
        given:
        def fresh = account('fresh')

        when:
        def statement = payments.statement(fresh.id)

        then:
        statement.lines().isEmpty()
        statement.balanceMinor() == 0
    }

    def "a transfer can be fetched back with its status"() {
        given:
        def alice = funded('alice', 10_000)
        def bob = account('bob')
        def posted = payments.transfer(new TransferRequest(alice.id, bob.id, 1_000, 'KES'))

        when:
        def fetched = payments.get(posted.id())

        then:
        fetched.id() == posted.id()
        fetched.status() == TransferStatus.POSTED.name()
        fetched.amountMinor() == 1_000
    }

    def "the review queue contains what was held and nothing else"() {
        given:
        def alice = funded('alice', 5_000_000)
        def bob = account('bob')
        payments.transfer(new TransferRequest(alice.id, bob.id, 1_000, 'KES'))
        def held = payments.transfer(heldTransfer(alice, bob))

        when:
        def queue = payments.awaitingReview()

        then:
        queue*.id().contains(held.id())
        queue.every { it.status() == TransferStatus.HELD_FOR_REVIEW.name() }
    }

    def "approving a held transfer posts the entries that were withheld"() {
        given:
        def alice = funded('alice', 5_000_000)
        def bob = account('bob')
        def held = payments.transfer(heldTransfer(alice, bob))

        expect: "nothing moved while it was held"
        ledger.balanceOf(bob.id) == 0

        when:
        def released = payments.decide(held.id(),
                new DecisionRequest('APPROVE', 'checked with the sender by phone'))

        then:
        released.status() == TransferStatus.POSTED.name()
        ledger.balanceOf(bob.id) == 1_000_000
        ledger.balanceOf(alice.id) == 4_000_000
        ledger.entriesFor(held.id()).size() == 2
        ledger.totalOfAllEntries() == 0
    }

    def "refusing a held transfer moves nothing and keeps the record"() {
        given:
        def alice = funded('alice', 5_000_000)
        def bob = account('bob')
        def held = payments.transfer(heldTransfer(alice, bob))

        when:
        def refused = payments.decide(held.id(),
                new DecisionRequest('REFUSE', 'sender did not recognise the recipient'))

        then:
        refused.status() == TransferStatus.REFUSED.name()
        ledger.entriesFor(held.id()).isEmpty()
        ledger.balanceOf(alice.id) == 5_000_000
        ledger.balanceOf(bob.id) == 0
    }

    def "approving rechecks the funds, because time has passed since the hold"() {
        given: "a transfer is held"
        def alice = funded('alice', 5_000_000)
        def bob = account('bob')
        def carol = account('carol')
        def held = payments.transfer(heldTransfer(alice, bob))

        and: "and the sender spends the money while it sits in the queue"
        // Posted straight through the ledger rather than through the risk
        // check, because this is setting up the state the reviewer arrives
        // into, not exercising the rules.
        ledger.post(alice.id, carol.id, 4_500_000, 'KES', RiskDecision.ALLOW)

        expect: "the account can no longer cover the held amount"
        ledger.balanceOf(alice.id) == 500_000

        when: "a reviewer approves it later"
        payments.decide(held.id(), new DecisionRequest('APPROVE', 'looks fine'))

        then: "trusting the check from when the hold was placed would overdraw the account"
        def e = thrown(LedgerException)
        e.message.contains('no longer holds enough')

        and: "and nothing moved"
        ledger.balanceOf(bob.id) == 0
        ledger.totalOfAllEntries() == 0
    }

    def "a transfer that was already decided cannot be decided again"() {
        given:
        def alice = funded('alice', 5_000_000)
        def bob = account('bob')
        def held = payments.transfer(heldTransfer(alice, bob))
        payments.decide(held.id(), new DecisionRequest('REFUSE', 'first verdict'))

        when:
        payments.decide(held.id(), new DecisionRequest('APPROVE', 'second thoughts'))

        then:
        thrown(LedgerException)
    }

    def "a posted transfer cannot be sent through review"() {
        given:
        def alice = funded('alice', 10_000)
        def bob = account('bob')
        def posted = payments.transfer(new TransferRequest(alice.id, bob.id, 1_000, 'KES'))

        when:
        payments.decide(posted.id(), new DecisionRequest('REFUSE', 'changed my mind'))

        then: "reversal is the way to undo something that already happened"
        thrown(LedgerException)
    }

    def "an approved transfer keeps the reason it was approved"() {
        given:
        def alice = funded('alice', 5_000_000)
        def bob = account('bob')
        def held = payments.transfer(heldTransfer(alice, bob))

        when:
        payments.decide(held.id(), new DecisionRequest('APPROVE', 'confirmed by phone'))
        def stored = ledger.requireTransfer(held.id())

        then: "who decided what and why is the point of keeping a review trail"
        stored.reviewReason == 'confirmed by phone'
        stored.reviewedAt != null
    }

    def "a released transfer can then be reversed like any other"() {
        given:
        def alice = funded('alice', 5_000_000)
        def bob = account('bob')
        def held = payments.transfer(heldTransfer(alice, bob))
        payments.decide(held.id(), new DecisionRequest('APPROVE', 'fine'))

        when:
        payments.reverse(held.id())

        then:
        ledger.balanceOf(alice.id) == 5_000_000
        ledger.balanceOf(bob.id) == 0
        ledger.totalOfAllEntries() == 0
    }
}

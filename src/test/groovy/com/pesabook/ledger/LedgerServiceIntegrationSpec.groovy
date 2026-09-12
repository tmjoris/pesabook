package com.pesabook.ledger

import com.pesabook.risk.RiskDecision
import com.pesabook.support.ContainerSpec
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * The ledger against a real PostgreSQL instance, where the check constraints
 * and the unique index on reverses are actually enforced.
 */
@SpringBootTest
class LedgerServiceIntegrationSpec extends ContainerSpec {

    @Autowired
    LedgerService ledger

    @Autowired
    AccountRepository accounts

    @Autowired
    TransferRepository transfers

    private Account account(String reference, String currency = 'KES') {
        ledger.openAccount(reference + '-' + UUID.randomUUID(), currency)
    }

    private Account funded(String reference, long amount) {
        def target = account(reference)
        ledger.fundFromExternal(target.id, amount, 'KES')
        target
    }

    def "a posted transfer moves the balance both ways"() {
        given:
        def alice = funded('alice', 10_000)
        def bob = account('bob')

        when:
        ledger.post(alice.id, bob.id, 2_500, 'KES', RiskDecision.ALLOW)

        then:
        ledger.balanceOf(alice.id) == 7_500
        ledger.balanceOf(bob.id) == 2_500
    }

    def "a transfer writes exactly two entries that cancel out"() {
        given:
        def alice = funded('alice', 10_000)
        def bob = account('bob')

        when:
        def transfer = ledger.post(alice.id, bob.id, 3_000, 'KES', RiskDecision.ALLOW)
        def entries = ledger.entriesFor(transfer.id)

        then:
        entries.size() == 2
        entries*.direction.toSet() == [Direction.DEBIT, Direction.CREDIT].toSet()
        entries*.signedAmount().sum() == 0
    }

    def "the ledger as a whole always sums to zero"() {
        given:
        def alice = funded('alice', 50_000)
        def bob = account('bob')
        def carol = account('carol')

        when: "a handful of movements including a reversal"
        def first = ledger.post(alice.id, bob.id, 5_000, 'KES', RiskDecision.ALLOW)
        ledger.post(alice.id, carol.id, 7_500, 'KES', RiskDecision.ALLOW)
        ledger.post(bob.id, carol.id, 1_000, 'KES', RiskDecision.ALLOW)
        ledger.reverse(first.id)

        then: "money was neither created nor destroyed along the way"
        ledger.totalOfAllEntries() == 0
    }

    def "refuses to move more than an account holds"() {
        given:
        def alice = funded('alice', 1_000)
        def bob = account('bob')

        when:
        ledger.post(alice.id, bob.id, 1_001, 'KES', RiskDecision.ALLOW)

        then:
        def e = thrown(LedgerException)
        e.message.contains('does not hold enough')

        and: "and nothing moved"
        ledger.balanceOf(alice.id) == 1_000
        ledger.balanceOf(bob.id) == 0
    }

    def "refuses a transfer between accounts in different currencies"() {
        given:
        def shillings = funded('kes-holder', 10_000)
        def euros = account('eur-holder', 'EUR')

        when:
        ledger.post(shillings.id, euros.id, 1_000, 'KES', RiskDecision.ALLOW)

        then:
        def e = thrown(LedgerException)
        e.message.contains('must hold the transfer currency')
    }

    def "refuses a transfer to the account it came from"() {
        given:
        def alice = funded('alice', 10_000)

        when:
        ledger.post(alice.id, alice.id, 500, 'KES', RiskDecision.ALLOW)

        then:
        thrown(LedgerException)
    }

    def "a reversal restores both balances without touching the original"() {
        given:
        def alice = funded('alice', 10_000)
        def bob = account('bob')
        def original = ledger.post(alice.id, bob.id, 4_000, 'KES', RiskDecision.ALLOW)

        when:
        def reversal = ledger.reverse(original.id)

        then: "balances are back where they started"
        ledger.balanceOf(alice.id) == 10_000
        ledger.balanceOf(bob.id) == 0

        and: "the original is still there, untouched, alongside its reversal"
        def stored = transfers.findById(original.id).get()
        stored.status == TransferStatus.POSTED
        ledger.entriesFor(original.id).size() == 2
        reversal.reverses == original.id
        ledger.entriesFor(reversal.id).size() == 2
    }

    def "a transfer can only be reversed once"() {
        given:
        def alice = funded('alice', 10_000)
        def bob = account('bob')
        def original = ledger.post(alice.id, bob.id, 4_000, 'KES', RiskDecision.ALLOW)
        ledger.reverse(original.id)

        when:
        ledger.reverse(original.id)

        then:
        def e = thrown(LedgerException)
        e.message.contains('already been reversed')
    }

    def "a reversal cannot itself be reversed"() {
        given:
        def alice = funded('alice', 10_000)
        def bob = account('bob')
        def original = ledger.post(alice.id, bob.id, 4_000, 'KES', RiskDecision.ALLOW)
        def reversal = ledger.reverse(original.id)

        when:
        ledger.reverse(reversal.id)

        then: "otherwise a pair of requests could bounce money back and forth"
        def e = thrown(LedgerException)
        e.message.contains('cannot itself be reversed')
    }

    def "a reversal is allowed even when it takes the recipient negative"() {
        given: "bob receives and then spends everything"
        def alice = funded('alice', 10_000)
        def bob = account('bob')
        def carol = account('carol')
        def original = ledger.post(alice.id, bob.id, 4_000, 'KES', RiskDecision.ALLOW)
        ledger.post(bob.id, carol.id, 4_000, 'KES', RiskDecision.ALLOW)

        when: "the first transfer turns out to have been misdirected"
        ledger.reverse(original.id)

        then: "refusing would leave the money where it does not belong"
        ledger.balanceOf(bob.id) == -4_000
        ledger.balanceOf(alice.id) == 10_000
        ledger.totalOfAllEntries() == 0
    }

    def "a held transfer records the attempt but moves nothing"() {
        given:
        def alice = funded('alice', 10_000)
        def bob = account('bob')

        when:
        def held = ledger.recordWithoutPosting(alice.id, bob.id, 6_000, 'KES',
                TransferStatus.HELD_FOR_REVIEW, RiskDecision.REVIEW)

        then:
        held.status == TransferStatus.HELD_FOR_REVIEW
        ledger.entriesFor(held.id).isEmpty()
        ledger.balanceOf(alice.id) == 10_000
        ledger.balanceOf(bob.id) == 0
    }

    def "refuses to open two accounts with the same reference"() {
        given:
        def reference = 'duplicate-' + UUID.randomUUID()
        ledger.openAccount(reference, 'KES')

        when:
        ledger.openAccount(reference, 'KES')

        then:
        thrown(LedgerException)
    }
}

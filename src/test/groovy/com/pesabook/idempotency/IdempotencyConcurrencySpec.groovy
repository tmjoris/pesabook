package com.pesabook.idempotency

import com.pesabook.api.dto.TransferRequest
import com.pesabook.ledger.Account
import com.pesabook.ledger.LedgerService
import com.pesabook.ledger.TransferRepository
import com.pesabook.risk.RiskDecision
import com.pesabook.support.ContainerSpec
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType

import java.util.concurrent.CountDownLatch

/**
 * The case the whole project exists for.
 *
 * A payment is sent, the network stalls, and the client retries. Sometimes the
 * retry arrives while the first attempt is still running. The money must move
 * once.
 *
 * Kenya processed 221.07 million agent cash in and cash out transactions in
 * July 2026 alone, about 7.1 million a day, so a race that happens rarely still
 * happens constantly. M-PESA's own result code table has a dedicated entry for
 * it: code 15, "Duplicate Detected".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyConcurrencySpec extends ContainerSpec {

    @LocalServerPort
    int port

    @Autowired
    TestRestTemplate rest

    @Autowired
    LedgerService ledger

    @Autowired
    TransferRepository transfers

    @Autowired
    ObjectMapper objectMapper

    private Account account(String reference) {
        ledger.openAccount(reference + '-' + UUID.randomUUID(), 'KES')
    }

    private Account funded(String reference, long amount) {
        def target = account(reference)
        ledger.fundFromExternal(target.id, amount, 'KES')
        target
    }

    private static HttpEntity<String> request(String body, String key) {
        def headers = new HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.set('Idempotency-Key', key)
        new HttpEntity<>(body, headers)
    }

    def "twenty concurrent retries of one key move the money once"() {
        given:
        def alice = funded('alice', 100_000)
        def bob = account('bob')
        def key = 'retry-' + UUID.randomUUID()
        def body = objectMapper.writeValueAsString(
                new TransferRequest(alice.id, bob.id, 2_500, 'KES'))
        def url = "http://localhost:${port}/v1/transfers"

        def attempts = 20
        def startTogether = new CountDownLatch(1)
        def responses = Collections.synchronizedList([])
        def threads = []

        when: "every thread fires the same request at the same moment"
        attempts.times {
            def t = new Thread({
                startTogether.await()
                responses << rest.exchange(url, HttpMethod.POST, request(body, key), String)
            })
            threads << t
            t.start()
        }
        startTogether.countDown()
        threads.each { it.join(60_000) }

        then: "every attempt came back with something"
        responses.size() == attempts

        and: "exactly one attempt did the work"
        responses.count {
            it.statusCode.value() == 201 && it.headers.getFirst('Idempotent-Replay') == 'false'
        } == 1

        and: "every other attempt was replayed or told to wait, never a second charge"
        responses.every { it.statusCode.value() == 201 || it.statusCode.value() == 409 }

        and: "and the money moved exactly once"
        ledger.balanceOf(alice.id) == 97_500
        ledger.balanceOf(bob.id) == 2_500

        and: "one transfer, and the ledger still balances"
        transfers.findAll().count {
            it.sourceAccount == alice.id && it.targetAccount == bob.id
        } == 1
        ledger.totalOfAllEntries() == 0
    }

    def "a retry after the first attempt finished is served the stored answer"() {
        given:
        def alice = funded('alice', 50_000)
        def bob = account('bob')
        def key = 'sequential-' + UUID.randomUUID()
        def body = objectMapper.writeValueAsString(
                new TransferRequest(alice.id, bob.id, 1_500, 'KES'))
        def url = "http://localhost:${port}/v1/transfers"

        when: "the client sends, gets no response it can see, and sends again"
        def first = rest.exchange(url, HttpMethod.POST, request(body, key), String)
        def second = rest.exchange(url, HttpMethod.POST, request(body, key), String)

        then: "both look successful to the caller"
        first.statusCode.value() == 201
        second.statusCode.value() == 201

        and: "but the second was a replay, not new work"
        first.headers.getFirst('Idempotent-Replay') == 'false'
        second.headers.getFirst('Idempotent-Replay') == 'true'
        first.body == second.body

        and: "and only one movement happened"
        ledger.balanceOf(alice.id) == 48_500
        ledger.balanceOf(bob.id) == 1_500
    }

    def "the same key carrying a different amount is refused"() {
        given:
        def alice = funded('alice', 50_000)
        def bob = account('bob')
        def key = 'reused-' + UUID.randomUUID()
        def url = "http://localhost:${port}/v1/transfers"
        def original = objectMapper.writeValueAsString(
                new TransferRequest(alice.id, bob.id, 1_000, 'KES'))
        def altered = objectMapper.writeValueAsString(
                new TransferRequest(alice.id, bob.id, 90_000, 'KES'))

        when:
        rest.exchange(url, HttpMethod.POST, request(original, key), String)
        def conflict = rest.exchange(url, HttpMethod.POST, request(altered, key), String)

        then: "serving the first answer would tell the caller 90,000 moved when 1,000 did"
        conflict.statusCode.value() == 422
        conflict.body.contains('idempotency_key_reused')

        and: "and the larger amount never moved"
        ledger.balanceOf(alice.id) == 49_000
    }

    def "a transfer without an idempotency key is refused outright"() {
        given:
        def alice = funded('alice', 10_000)
        def bob = account('bob')
        def headers = new HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        def body = objectMapper.writeValueAsString(
                new TransferRequest(alice.id, bob.id, 500, 'KES'))

        when:
        def response = rest.exchange("http://localhost:${port}/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(body, headers), String)

        then: "the unsafe path should not be the one a caller gets by forgetting something"
        response.statusCode.value() == 400
        ledger.balanceOf(alice.id) == 10_000
    }

    def "concurrent reversals of one transfer reverse it once"() {
        given:
        def alice = funded('alice', 20_000)
        def bob = account('bob')
        def original = ledger.post(alice.id, bob.id, 5_000, 'KES', RiskDecision.ALLOW)
        def url = "http://localhost:${port}/v1/transfers/${original.id}/reversals"

        def attempts = 8
        def startTogether = new CountDownLatch(1)
        def responses = Collections.synchronizedList([])
        def threads = []

        when: "several operators hit reverse at once, each with their own key"
        attempts.times {
            def t = new Thread({
                startTogether.await()
                responses << rest.exchange(url, HttpMethod.POST,
                        request('', 'reversal-' + UUID.randomUUID()), String)
            })
            threads << t
            t.start()
        }
        startTogether.countDown()
        threads.each { it.join(60_000) }

        then: "the unique constraint on reverses lets exactly one through"
        responses.size() == attempts
        responses.count { it.statusCode.value() == 201 } == 1

        and: "the losers are told the state conflicts, not that the server broke"
        responses.every { it.statusCode.value() == 201 || it.statusCode.value() == 422 }

        and: "the balances are back where they started, not further"
        ledger.balanceOf(alice.id) == 20_000
        ledger.balanceOf(bob.id) == 0
        ledger.totalOfAllEntries() == 0
    }
}

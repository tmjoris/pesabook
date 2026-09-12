package com.pesabook.idempotency

import spock.lang.Specification
import spock.lang.Subject

class IdempotencyServiceSpec extends Specification {

    IdempotencyStore store = Mock()

    @Subject
    IdempotencyService service = new IdempotencyService(store, new RequestFingerprint())

    def "does the work and stores the answer when the key is new"() {
        given:
        def transferId = UUID.randomUUID()
        store.tryClaim('key-1', _) >> Optional.empty()

        when:
        def outcome = service.execute('key-1', '{"amount":100}',
                { new IdempotencyService.Work(201, '{"id":"x"}', transferId) })

        then:
        1 * store.complete('key-1', { it.httpStatus() == 201 && it.transferId() == transferId })
        !outcome.replayed()
        outcome.httpStatus() == 201
    }

    def "replays the stored answer for a repeat of the same request"() {
        given: "a completed record whose fingerprint matches the body below"
        def fingerprint = new RequestFingerprint().of('{"amount":100}')
        def record = new IdempotencyRecord('key-1', fingerprint)
        record.complete(201, '{"id":"the-original"}', UUID.randomUUID())
        store.tryClaim('key-1', _) >> Optional.of(record)

        when:
        def outcome = service.execute('key-1', '{"amount":100}',
                { throw new IllegalStateException('the work must not run twice') })

        then:
        outcome.replayed()
        outcome.httpStatus() == 201
        outcome.body() == '{"id":"the-original"}'
    }

    def "refuses a key that comes back carrying a different request"() {
        given:
        def record = new IdempotencyRecord('key-1', new RequestFingerprint().of('{"amount":100}'))
        record.complete(201, '{}', UUID.randomUUID())
        store.tryClaim('key-1', _) >> Optional.of(record)

        when: "the same key arrives with a larger amount"
        service.execute('key-1', '{"amount":999999}', { null })

        then: "replaying the old answer would hide a client bug"
        thrown(IdempotencyConflictException)
    }

    def "tells a caller to wait when another request still holds the key"() {
        given:
        def inProgress = new IdempotencyRecord('key-1', new RequestFingerprint().of('body'))
        store.tryClaim('key-1', _) >> Optional.of(inProgress)

        when:
        service.execute('key-1', 'body', { null })

        then:
        thrown(IdempotencyInProgressException)
    }

    def "releases the key when the work fails, so a retry is possible"() {
        given:
        store.tryClaim('key-1', _) >> Optional.empty()

        when:
        service.execute('key-1', 'body', { throw new IllegalStateException('database went away') })

        then:
        1 * store.release('key-1')
        0 * store.complete(_, _)
        thrown(IllegalStateException)
    }

    def "requires a key"() {
        when:
        service.execute(key, 'body', { null })

        then:
        thrown(IllegalArgumentException)

        where:
        key << [null, '', '   ']
    }

    def "treats requests differing only in whitespace as different requests"() {
        given:
        def record = new IdempotencyRecord('key-1', new RequestFingerprint().of('{"a":1}'))
        record.complete(201, '{}', UUID.randomUUID())
        store.tryClaim('key-1', _) >> Optional.of(record)

        when: "the body is semantically the same but not byte identical"
        service.execute('key-1', '{"a": 1}', { null })

        then: "the fingerprint is over bytes, so this is reported rather than assumed safe"
        thrown(IdempotencyConflictException)
    }
}

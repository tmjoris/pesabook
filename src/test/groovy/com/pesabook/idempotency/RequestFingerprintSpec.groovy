package com.pesabook.idempotency

import spock.lang.Specification
import spock.lang.Subject

class RequestFingerprintSpec extends Specification {

    @Subject
    RequestFingerprint fingerprints = new RequestFingerprint()

    def "the same body always gives the same fingerprint"() {
        expect:
        fingerprints.of('{"amount":100}') == fingerprints.of('{"amount":100}')
    }

    def "a different body gives a different fingerprint"() {
        expect:
        fingerprints.of('{"amount":100}') != fingerprints.of('{"amount":101}')
    }

    def "produces a fixed width hex digest"() {
        expect:
        fingerprints.of(body).length() == 64
        fingerprints.of(body) ==~ /[0-9a-f]{64}/

        where:
        body << ['', '{}', 'a' * 10_000, '{"currency":"KES"}']
    }

    def "handles a null body rather than throwing"() {
        when:
        def result = fingerprints.of(null)

        then:
        noExceptionThrown()
        result.length() == 64
    }

    def "is sensitive to a transposition that would change where money goes"() {
        given:
        def one = '{"source":"a","target":"b"}'
        def other = '{"source":"b","target":"a"}'

        expect:
        fingerprints.of(one) != fingerprints.of(other)
    }
}

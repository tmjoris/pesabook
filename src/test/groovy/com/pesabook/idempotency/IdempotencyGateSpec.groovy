package com.pesabook.idempotency

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import spock.lang.Specification
import spock.lang.Subject

import java.time.Duration

/**
 * The gate is a fast reject in front of the durable claim, never an authority.
 *
 * The specs that matter most here are the ones about Redis being unavailable.
 * A cache in front of a rule about money must not be able to take the system
 * down or, worse, make it wrong.
 */
class IdempotencyGateSpec extends Specification {

    StringRedisTemplate redis = Mock()
    ValueOperations<String, String> values = Mock()

    @Subject
    IdempotencyGate gate = new IdempotencyGate(redis, 60)

    def "takes the marker when nobody holds it"() {
        given:
        redis.opsForValue() >> values
        values.setIfAbsent('pesabook:idem:abc', 'in-flight', Duration.ofSeconds(60)) >> true

        expect:
        gate.tryAcquire('abc')
    }

    def "refuses when someone else already holds it"() {
        given:
        redis.opsForValue() >> values
        values.setIfAbsent(_, _, _) >> false

        expect:
        !gate.tryAcquire('abc')
    }

    def "treats a null answer from Redis as not acquired"() {
        given:
        redis.opsForValue() >> values
        values.setIfAbsent(_, _, _) >> null

        expect: "Boolean.TRUE.equals guards against the unboxing trap"
        !gate.tryAcquire('abc')
    }

    def "lets the caller through when Redis is unreachable"() {
        given:
        redis.opsForValue() >> { throw new RuntimeException('connection refused') }

        expect: "failing closed would turn a cache outage into a payments outage"
        gate.tryAcquire('abc')
    }

    def "lets the caller through when Redis throws mid call"() {
        given:
        redis.opsForValue() >> values
        values.setIfAbsent(_, _, _) >> { throw new RuntimeException('timeout') }

        expect:
        gate.tryAcquire('abc')
    }

    def "swallows a failure to release, because the key expires anyway"() {
        given:
        redis.delete(_ as String) >> { throw new RuntimeException('gone') }

        when:
        gate.release('abc')

        then:
        noExceptionThrown()
    }

    def "namespaces its keys so it cannot collide with anything else in the instance"() {
        given:
        String seen = null
        redis.opsForValue() >> values
        values.setIfAbsent(_, _, _) >> { args -> seen = args[0]; true }

        when:
        gate.tryAcquire('some-key')

        then:
        seen == 'pesabook:idem:some-key'
    }

    def "expires the marker so a crashed request cannot block retries forever"() {
        given:
        Duration seen = null
        redis.opsForValue() >> values
        values.setIfAbsent(_, _, _) >> { args -> seen = args[2]; true }

        when:
        new IdempotencyGate(redis, 30).tryAcquire('abc')

        then:
        seen == Duration.ofSeconds(30)
    }
}

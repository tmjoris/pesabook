package com.pesabook.idempotency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * A short lived gate in front of the durable claim.
 *
 * This exists to reject a duplicate before it reaches the database, not to
 * decide anything. PostgreSQL remains the authority: a caller that passes this
 * gate still has to win the insert, and a caller that Redis has never heard of
 * is still allowed through to try. That ordering matters, because it means
 * losing Redis entirely costs throughput and nothing else. The system is still
 * correct with Redis switched off, which is the property worth protecting when
 * a cache sits in front of a rule about money.
 *
 * The lock is deliberately short. It covers the seconds a request is actually
 * running, so a retry arriving mid flight is turned away cheaply. Anything
 * longer starts denying legitimate retries after a crash, which is the failure
 * this whole layer exists to avoid.
 */
@Component
public class IdempotencyGate {

    private static final String PREFIX = "pesabook:idem:";

    private final StringRedisTemplate redis;
    private final Duration lockFor;

    public IdempotencyGate(StringRedisTemplate redis,
                           @Value("${pesabook.idempotency.lock-seconds:60}") long lockSeconds) {
        this.redis = redis;
        this.lockFor = Duration.ofSeconds(lockSeconds);
    }

    /**
     * Tries to take the in flight marker for a key.
     *
     * @return true when this caller took it, false when someone else holds it
     */
    public boolean tryAcquire(String key) {
        try {
            Boolean taken = redis.opsForValue()
                    .setIfAbsent(PREFIX + key, "in-flight", lockFor);
            return Boolean.TRUE.equals(taken);
        } catch (RuntimeException e) {
            // Redis is unreachable. Let the caller through to the database,
            // which enforces the real constraint. Failing closed here would
            // turn a cache outage into a payments outage.
            return true;
        }
    }

    /**
     * Gives up the marker once the work has finished and its result is durable.
     */
    public void release(String key) {
        try {
            redis.delete(PREFIX + key);
        } catch (RuntimeException e) {
            // The key expires on its own, so a failure here costs nothing but
            // a slightly longer wait for a retry of the same key.
        }
    }
}

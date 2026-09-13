package com.pesabook.idempotency;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Makes a request that moves money safe to repeat.
 *
 * The contract follows the one Stripe documents. The first request for a key
 * does the work and its response is stored. A later request carrying the same
 * key and the same body is given that stored response rather than doing the
 * work again. A request carrying the same key and a different body is refused,
 * because that is a client bug rather than a retry.
 *
 * The claim is a database insert on a primary key, not a read followed by a
 * write. Two concurrent requests both attempt the insert, the database lets one
 * through, and the other is told the key is taken. Checking and then inserting
 * would leave a window between the two where both callers believe they are
 * first, and that window is exactly where a retry over a slow mobile network
 * lands.
 */
@Service
public class IdempotencyService {

    private final IdempotencyStore store;
    private final IdempotencyGate gate;
    private final RequestFingerprint fingerprints;

    public IdempotencyService(IdempotencyStore store,
                              IdempotencyGate gate,
                              RequestFingerprint fingerprints) {
        this.store = store;
        this.gate = gate;
        this.fingerprints = fingerprints;
    }

    /**
     * Runs the work at most once for the given key.
     *
     * @param key         the caller supplied idempotency key
     * @param requestBody the request, used to detect a key reused with different content
     * @param work        what to do if this caller wins the claim
     */
    public IdempotentOutcome execute(String key, String requestBody, Supplier<Work> work) {

        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("An idempotency key is required");
        }

        String fingerprint = fingerprints.of(requestBody);

        // Redis turns away a retry that arrives while the first attempt is still
        // running, without spending a database round trip on it. It is only ever
        // a fast path: a caller it lets through still has to win the insert
        // below, and if Redis is down it lets everyone through.
        if (!gate.tryAcquire(key)) {
            Optional<IdempotencyRecord> existing = store.find(key);
            if (existing.isPresent()) {
                return replayOrRefuse(existing.get(), fingerprint);
            }
            throw new IdempotencyInProgressException(key);
        }

        Optional<IdempotencyRecord> existing;
        try {
            existing = store.tryClaim(key, fingerprint);
        } catch (RuntimeException e) {
            gate.release(key);
            throw e;
        }

        if (existing.isPresent()) {
            // Someone else already owns the durable claim, so this caller never
            // had the right to the marker it just took.
            gate.release(key);
            return replayOrRefuse(existing.get(), fingerprint);
        }

        Work result;
        try {
            result = work.get();
        } catch (RuntimeException e) {
            // Release the claim so the caller can retry. Holding it would turn a
            // transient failure into a permanent refusal.
            store.release(key);
            gate.release(key);
            throw e;
        }

        store.complete(key, result);
        gate.release(key);
        return new IdempotentOutcome(false, result.httpStatus(), result.body());
    }

    private IdempotentOutcome replayOrRefuse(IdempotencyRecord record, String fingerprint) {
        if (!record.matches(fingerprint)) {
            throw new IdempotencyConflictException(record.getKey());
        }
        if (!record.isCompleted()) {
            throw new IdempotencyInProgressException(record.getKey());
        }
        return new IdempotentOutcome(true, record.getResponseStatus(), record.getResponseBody());
    }

    /**
     * The outcome of the work, as it should be stored and replayed.
     */
    public record Work(int httpStatus, String body, UUID transferId) {
    }
}

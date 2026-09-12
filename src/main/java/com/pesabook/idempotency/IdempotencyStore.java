package com.pesabook.idempotency;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The database side of the idempotency contract.
 *
 * This is a separate bean rather than a few methods on IdempotencyService for a
 * reason that is easy to get wrong. Spring applies @Transactional through a
 * proxy, so a call from one method of a bean to another method of the same bean
 * never passes through it and the annotation does nothing. Each claim and each
 * completion has to commit on its own, independently of whatever transaction
 * the work is running in, so they have to be called across a bean boundary.
 */
@Component
public class IdempotencyStore {

    private final IdempotencyRepository records;

    public IdempotencyStore(IdempotencyRepository records) {
        this.records = records;
    }

    /**
     * Attempts to claim the key.
     *
     * @return empty when this caller won the claim, or the existing record when
     *         someone else already holds it
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencyRecord> tryClaim(String key, String fingerprint) {
        int inserted = records.insertIfAbsent(key, fingerprint);

        if (inserted == 1) {
            return Optional.empty();
        }

        return Optional.of(records.findById(key)
                .orElseThrow(() -> new IllegalStateException(
                        "Key " + key + " was taken and then vanished")));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, IdempotencyService.Work result) {
        IdempotencyRecord record = records.findById(key)
                .orElseThrow(() -> new IllegalStateException(
                        "Key " + key + " vanished part way through the request"));
        record.complete(result.httpStatus(), result.body(), result.transferId());
        records.save(record);
    }

    /**
     * Gives up a claim so the caller can genuinely retry.
     *
     * Without this, a request that failed for an unrelated reason would leave
     * its key permanently claimed, and the retry it was hoping for would be
     * refused as already in progress.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key) {
        records.deleteById(key);
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> find(String key) {
        return records.findById(key);
    }
}

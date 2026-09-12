package com.pesabook.idempotency;

/**
 * What the caller gets back for a key.
 *
 * @param replayed   true when this is the stored answer from an earlier request
 *                   rather than the result of work done now
 * @param httpStatus the status the first request returned
 * @param body       the body the first request returned
 */
public record IdempotentOutcome(boolean replayed, int httpStatus, String body) {
}

package com.pesabook.idempotency;

/**
 * Another request holding this key is still running.
 *
 * The caller should wait and retry rather than be served a half finished
 * answer. Stripe behaves the same way, and the alternative, letting both
 * through, is exactly the double charge the key exists to prevent.
 */
public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException(String key) {
        super("A request holding idempotency key " + key + " is still in progress");
    }
}

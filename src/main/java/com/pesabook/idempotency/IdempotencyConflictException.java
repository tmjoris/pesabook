package com.pesabook.idempotency;

/**
 * The same key arrived carrying a different request.
 *
 * This is not a retry. Either the client reused a key it should have rotated,
 * or it changed the payload underneath one. Serving the first answer would hide
 * a real bug and could convince a caller that money moved as they last asked,
 * when it moved as they first asked.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String key) {
        super("Idempotency key " + key + " was already used with a different request");
    }
}

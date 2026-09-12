package com.pesabook.idempotency;

public enum IdempotencyStatus {

    /** A request holding this key is running now. */
    IN_PROGRESS,

    /** A request holding this key finished, and its answer is stored. */
    COMPLETED
}

package com.pesabook.ledger;

/**
 * Thrown when a movement would break a rule the ledger exists to hold.
 */
public class LedgerException extends RuntimeException {

    public LedgerException(String message) {
        super(message);
    }
}

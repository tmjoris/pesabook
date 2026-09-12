package com.pesabook.ledger;

public enum TransferStatus {

    /** Entries were posted and the money moved. */
    POSTED,

    /** The risk check asked for a human decision, so no entries were posted. */
    HELD_FOR_REVIEW,

    /** The risk check refused it, so no entries were posted. */
    BLOCKED
}

package com.pesabook.api.dto;

import com.pesabook.ledger.Direction;
import com.pesabook.ledger.LedgerEntry;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of an account statement.
 *
 * signedAmount is included alongside the raw amount because a reader working
 * out a running balance should not have to know that a debit is negative.
 */
public record LedgerEntryResponse(UUID id,
                                  UUID transferId,
                                  Direction direction,
                                  long amountMinor,
                                  long signedAmountMinor,
                                  String currency,
                                  Instant createdAt) {

    public static LedgerEntryResponse of(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getTransferId(),
                entry.getDirection(),
                entry.getAmountMinor(),
                entry.signedAmount(),
                entry.getCurrency(),
                entry.getCreatedAt());
    }
}

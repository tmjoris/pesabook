package com.pesabook.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * An account's entries with a running balance.
 *
 * The running balance is computed here rather than stored, the same way the
 * account balance is, so the statement and the balance cannot disagree.
 */
public record StatementResponse(UUID accountId,
                                String reference,
                                String currency,
                                long balanceMinor,
                                List<StatementLine> lines) {

    public record StatementLine(LedgerEntryResponse entry, long runningBalanceMinor) {
    }
}

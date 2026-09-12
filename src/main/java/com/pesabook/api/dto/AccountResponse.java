package com.pesabook.api.dto;

import com.pesabook.ledger.Account;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(UUID id,
                              String reference,
                              String currency,
                              long balanceMinor,
                              Instant createdAt) {

    public static AccountResponse of(Account account, long balanceMinor) {
        return new AccountResponse(account.getId(), account.getReference(),
                account.getCurrency(), balanceMinor, account.getCreatedAt());
    }
}

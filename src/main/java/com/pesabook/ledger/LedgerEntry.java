package com.pesabook.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One side of one movement.
 *
 * Entries are written once and never updated or deleted. There is deliberately
 * no setter on this class and no update path in the repositories: a mistake is
 * corrected by posting further entries, which leaves the original visible.
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Direction direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(UUID transferId, UUID accountId, Direction direction,
                       long amountMinor, String currency) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("A ledger entry must carry a positive amount");
        }
        this.id = UUID.randomUUID();
        this.transferId = transferId;
        this.accountId = accountId;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    /**
     * The amount as it contributes to a balance, negative for a debit.
     * Summing this across a transfer's entries has to give zero.
     */
    public long signedAmount() {
        return direction == Direction.CREDIT ? amountMinor : -amountMinor;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Direction getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

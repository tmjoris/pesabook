package com.pesabook.ledger;

import com.pesabook.risk.RiskDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfer")
public class Transfer {

    @Id
    private UUID id;

    @Column(name = "source_account", nullable = false)
    private UUID sourceAccount;

    @Column(name = "target_account", nullable = false)
    private UUID targetAccount;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransferStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_decision", nullable = false, length = 16)
    private RiskDecision riskDecision;

    /**
     * The transfer this one reverses, if any. The database carries a unique
     * constraint on this column, so a second attempt to reverse the same
     * transfer fails on insert rather than depending on a check that two
     * concurrent requests could both pass.
     */
    @Column(name = "reverses")
    private UUID reverses;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Transfer() {
    }

    public Transfer(UUID sourceAccount, UUID targetAccount, long amountMinor,
                    String currency, TransferStatus status, RiskDecision riskDecision,
                    UUID reverses) {
        this.id = UUID.randomUUID();
        this.sourceAccount = sourceAccount;
        this.targetAccount = targetAccount;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.riskDecision = riskDecision;
        this.reverses = reverses;
        this.createdAt = Instant.now();
    }

    public boolean isReversal() {
        return reverses != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceAccount() {
        return sourceAccount;
    }

    public UUID getTargetAccount() {
        return targetAccount;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public RiskDecision getRiskDecision() {
        return riskDecision;
    }

    public UUID getReverses() {
        return reverses;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

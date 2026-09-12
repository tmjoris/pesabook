package com.pesabook.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * What happened the first time a key was seen.
 *
 * The key is the primary key of the table, which is the mechanism rather than a
 * detail: two concurrent requests carrying the same key both try to insert this
 * row, the database lets exactly one of them win, and the loser learns it lost
 * from the constraint violation rather than from a check it might have raced.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key")
    private String key;

    /**
     * A hash of the request body. Stripe rejects a key that comes back with
     * different parameters, because that is a client bug rather than a retry,
     * and silently serving the first answer would hide it.
     */
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String key, String requestFingerprint) {
        this.key = key;
        this.requestFingerprint = requestFingerprint;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = Instant.now();
    }

    public void complete(int responseStatus, String responseBody, UUID transferId) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.transferId = transferId;
        this.completedAt = Instant.now();
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    public boolean matches(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }

    public String getKey() {
        return key;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}

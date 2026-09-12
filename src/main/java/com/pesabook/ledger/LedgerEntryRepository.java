package com.pesabook.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransferId(UUID transferId);

    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    /**
     * Used by the invariant check. Across the whole ledger this has to be zero,
     * because every transfer posts a balanced pair.
     */
    @Query("""
            select coalesce(sum(case when e.direction = com.pesabook.ledger.Direction.CREDIT
                                     then e.amountMinor else -e.amountMinor end), 0)
            from LedgerEntry e
            """)
    long sumOfAllSignedAmounts();

    @Query("""
            select count(e) from LedgerEntry e
            where e.accountId = :accountId and e.createdAt >= :since
            """)
    long countForAccountSince(@Param("accountId") UUID accountId, @Param("since") Instant since);
}

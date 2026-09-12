package com.pesabook.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByReverses(UUID reverses);

    boolean existsByReverses(UUID reverses);

    /**
     * Counts every attempt, not only the ones that were posted.
     *
     * A sender who keeps trying after being held or blocked is a stronger
     * signal than one who stops, so counting only successes would let a
     * determined attacker sit just under the threshold forever.
     */
    @Query("""
            select count(t) from Transfer t
            where t.sourceAccount = :accountId
              and t.createdAt >= :since
              and t.reverses is null
            """)
    long countAttemptsFromAccountSince(@Param("accountId") UUID accountId,
                                       @Param("since") Instant since);

    @Query("""
            select count(t) from Transfer t
            where t.sourceAccount = :accountId
              and t.createdAt >= :since
              and t.status = com.pesabook.ledger.TransferStatus.POSTED
            """)
    long countPostedFromAccountSince(@Param("accountId") UUID accountId,
                                     @Param("since") Instant since);

    @Query("""
            select t.amountMinor from Transfer t
            where t.sourceAccount = :accountId
              and t.status = com.pesabook.ledger.TransferStatus.POSTED
            order by t.createdAt desc
            limit :sampleSize
            """)
    List<Long> recentAmountsFromAccount(@Param("accountId") UUID accountId,
                                        @Param("sampleSize") int sampleSize);

    @Query("""
            select count(t) from Transfer t
            where t.sourceAccount = :sourceAccount
              and t.targetAccount = :targetAccount
              and t.status = com.pesabook.ledger.TransferStatus.POSTED
            """)
    long countPostedBetween(@Param("sourceAccount") UUID sourceAccount,
                            @Param("targetAccount") UUID targetAccount);
}

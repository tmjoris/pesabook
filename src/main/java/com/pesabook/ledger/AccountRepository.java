package com.pesabook.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByReference(String reference);

    boolean existsByReference(String reference);

    /**
     * The balance is computed from the entries every time rather than read from
     * a column. Storing a balance means keeping two things in step, and the
     * entries are the ones that can be audited.
     */
    @Query("""
            select coalesce(sum(case when e.direction = com.pesabook.ledger.Direction.CREDIT
                                     then e.amountMinor else -e.amountMinor end), 0)
            from LedgerEntry e
            where e.accountId = :accountId
            """)
    long balanceMinor(@Param("accountId") UUID accountId);
}

package com.pesabook.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    /**
     * Claims a key, or reports that someone else already holds it.
     *
     * This is a native insert rather than a call to save() for a reason that
     * cost a CI run to find. Spring Data decides whether save() should insert
     * or merge by asking whether the entity looks new, and an entity whose id
     * is assigned by the application never looks new. So save() issued a select
     * followed by an update, quietly overwriting the record belonging to the
     * request that got there first. No constraint was violated, no exception
     * was thrown, and the second request cheerfully did the work again. On an
     * endpoint that moves money that is the exact failure the key exists to
     * prevent.
     *
     * "on conflict do nothing" makes the claim one atomic statement whose
     * return value says plainly whether this caller won: 1 for inserted, 0 for
     * already taken.
     */
    @Modifying
    @Query(value = """
            insert into idempotency_record
                (idempotency_key, request_fingerprint, status, created_at)
            values (:key, :fingerprint, 'IN_PROGRESS', now())
            on conflict (idempotency_key) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("fingerprint") String fingerprint);
}
